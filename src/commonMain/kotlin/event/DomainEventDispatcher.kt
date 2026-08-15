package event

import neton.core.event.DomainEvent
import neton.core.event.DomainEventBus
import neton.core.event.DomainEventListener
import neton.core.event.DomainEventStore
import neton.core.event.StoredEventRecord
import kotlin.coroutines.cancellation.CancellationException

/**
 * 落库事件的投递器：领取到期记录 → 交回对应监听者 → 成功标记完成、失败按退避重排。
 *
 * 由调度任务周期性调用（框架不自带任务，交给使用方按其调度设施接入，
 * 这样投递频率与并发由部署方决定）。
 *
 * **投递语义是至少一次**：标记完成前进程崩溃后，该记录会滞留在"投递中"，
 * 由 [DispatchConfig.visibilityTimeoutMillis] 超时后被重新领取，因此同一条可能被投递多次，
 * **监听者必须自己幂等**。改成恰好一次需要把"业务副作用"和"标记完成"放进同一个事务，
 * 而副作用往往在外部系统里，那是分布式事务的范畴，代价远大于让消费方做幂等。
 *
 * 反过来说，没有这个超时回收，崩溃的那条就永远不会被再次领取——那不是"至少一次"，
 * 是静默丢失。
 */
class DomainEventDispatcher(
    private val store: DomainEventStore,
    private val bus: DomainEventBus,
    /** 把落库的载荷还原成事件对象；返回 null 表示无法还原（类型已下线等）。 */
    private val codec: neton.core.event.DomainEventCodec,
    private val config: DispatchConfig = DispatchConfig(),
    private val onError: (StoredEventFailure) -> Unit = {},
) {

    /**
     * 跑一轮投递。
     *
     * @return 本轮成功投递的条数
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun dispatchOnce(now: Long): Int {
        val batch = store.claimDue(now, config.batchSize, staleBefore = now - config.visibilityTimeoutMillis)
        var delivered = 0

        for (record in batch) {
            val listener = bus.listenerOf(record.listenerId)

            // decode 放在 try 里：JSON 解码遇到坏数据是**抛异常**而不是返回 null，
            // 放在外面的话一条毒消息会让整批中断，其余记录不处理，超时后毒消息还会重现、再中断。
            // 解码异常与"返回 null"同等对待——重试多少次都解不出来，直接终态。
            val event: DomainEvent? = try {
                codec.decode(record.eventType, record.payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                settlePermanently(record, now, "载荷解码异常: ${e.message ?: e::class.simpleName}")
                continue
            }

            // 这三种情况重试多少次结果都一样，直接转终态，留在库里等人处理，
            // 而不是无限占用后续批次。第三种（解出来的对象类型不对）不能交给监听者：
            // 那会在 onEvent 里以 ClassCastException 的形式失败，被当成普通失败反复重试。
            val permanentReason = when {
                listener == null -> "监听者不存在: ${record.listenerId}"
                event == null -> "载荷无法还原: ${record.eventType}"
                !listener.eventType.isInstance(event) ->
                    "载荷类型不匹配: 监听者 ${record.listenerId} 订阅 ${listener.eventType.simpleName}，" +
                        "解出的是 ${event::class.simpleName}"
                else -> null
            }
            if (permanentReason != null) {
                settlePermanently(record, now, permanentReason)
                continue
            }

            try {
                (listener as DomainEventListener<DomainEvent>).onEvent(event!!)
                // 令牌不匹配返回 false：本记录已被别的领取者接管，本次投递结果作废，
                // 不计入 delivered。监听者已经执行过一次——这就是"至少一次"的含义。
                if (store.markDelivered(record.id, record.claimToken, now)) delivered++
            } catch (e: CancellationException) {
                // 关闭 / 取消不是投递失败：不能记成错误、不能吞掉信号。
                // 记录留在"投递中"，由可见性超时回收后重投。
                throw e
            } catch (e: Throwable) {
                val attempts = record.attempts + 1
                val next = if (attempts >= config.maxAttempts) null else now + backoffMillis(attempts)
                val reason = e.message ?: e::class.simpleName ?: "未知错误"
                // 落定失败（令牌已换）说明所有权已丢，这条失败已经不归本 worker 管，
                // 不能再上报——那会制造一条并不存在的重试/终态告警。
                if (store.markFailed(record.id, record.claimToken, now, next, reason)) {
                    onError(StoredEventFailure(record.id, record.listenerId, reason, permanent = next == null))
                }
            }
        }
        return delivered
    }

    /** 转终态并上报；令牌已换（返回 false）则静默——所有权已不在本 worker。 */
    private suspend fun settlePermanently(record: StoredEventRecord, now: Long, reason: String) {
        if (store.markFailed(record.id, record.claimToken, now, null, reason)) {
            onError(StoredEventFailure(record.id, record.listenerId, reason, permanent = true))
        }
    }

    /**
     * 指数退避并封顶。
     *
     * 不封顶的话尾部重试会被推到几天之后，等于永远不再投递；
     * 封顶后即使多次失败也仍以固定节奏重试，恢复后能自愈。
     */
    private fun backoffMillis(attempts: Int): Long {
        var delay = config.baseBackoffMillis
        repeat(attempts - 1) {
            delay *= 2
            if (delay >= config.maxBackoffMillis) return config.maxBackoffMillis
        }
        return minOf(delay, config.maxBackoffMillis)
    }
}

data class DispatchConfig(
    val batchSize: Int = 100,
    /** 超过该次数转终态失败，等待人工介入。 */
    val maxAttempts: Int = 8,
    val baseBackoffMillis: Long = 5_000,
    val maxBackoffMillis: Long = 10 * 60 * 1000,
    /**
     * "投递中"多久未落定即视为滞留，可被重新领取。
     *
     * 要明显大于单条投递的最长耗时，否则正在正常执行的记录会被另一个节点重复领走；
     * 又不能太大，否则崩溃后的恢复要等很久。默认 5 分钟。
     */
    val visibilityTimeoutMillis: Long = 5 * 60 * 1000,
)

data class StoredEventFailure(
    val id: Long,
    val listenerId: String,
    val reason: String,
    /** true 表示不再重试。 */
    val permanent: Boolean,
)
