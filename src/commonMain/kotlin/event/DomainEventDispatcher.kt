package event

import neton.core.event.DomainEvent
import neton.core.event.DomainEventBus
import neton.core.event.DomainEventListener
import neton.core.event.DomainEventStore

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
            val event = codec.decode(record.eventType, record.payload)

            // 监听者下线或载荷无法还原：重试多少次都一样，直接转终态，
            // 留在库里等人处理，而不是无限占用后续批次。
            if (listener == null || event == null) {
                val reason = if (listener == null) "监听者不存在: ${record.listenerId}"
                             else "载荷无法还原: ${record.eventType}"
                store.markFailed(record.id, now, null, reason)
                onError(StoredEventFailure(record.id, record.listenerId, reason, permanent = true))
                continue
            }

            try {
                (listener as DomainEventListener<DomainEvent>).onEvent(event)
                store.markDelivered(record.id, now)
                delivered++
            } catch (e: Throwable) {
                val attempts = record.attempts + 1
                val next = if (attempts >= config.maxAttempts) null else now + backoffMillis(attempts)
                val reason = e.message ?: e::class.simpleName ?: "未知错误"
                store.markFailed(record.id, now, next, reason)
                onError(StoredEventFailure(record.id, record.listenerId, reason, permanent = next == null))
            }
        }
        return delivered
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
