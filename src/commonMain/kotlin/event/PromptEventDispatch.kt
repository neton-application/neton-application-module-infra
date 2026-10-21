package event

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * 落库事件的**即时投递**：发布方写入一条待投递记录后立即唤醒本节点投递，而不是等周期任务的下一轮。
 *
 * 为什么需要：周期任务 `infra-domain-event-dispatch` 每 10 秒一轮，可重试事件的端到端延迟最长 10 秒。
 * 对「后台发布 → 门户缓存失效」这类用户可感知的链路（site-module §11：1 秒内收到 `obj:` 失效）太慢。
 *
 * 怎么做：[signal] 由 [PostgresDomainEventStore.append] 在写入后调用（还在发布方事务里，记录尚未提交），
 * 所以唤醒后按 [settleDelaysMillis] 的节奏试几轮——第一轮通常就能领到刚提交的记录，事务较长时后面几轮兜底。
 * 新的唤醒会从头开始这个节奏。领取用 `FOR UPDATE SKIP LOCKED`，与周期任务、其他节点并发也不会重复投递；
 * 周期任务照常运行，负责重试、滞留回收与其他节点写入的记录——本类只缩短「刚写入」这一段的延迟。
 *
 * 唤醒是本进程内的：多节点部署时，记录由写入它的节点即时投递，其余节点照旧靠周期任务兜底。
 */
class PromptEventDispatch(
    /** 跑一轮投递，返回投递条数（[DomainEventDispatcher.dispatchOnce]）。 */
    private val dispatchOnce: suspend () -> Int,
    /** 唤醒后每一轮之前的等待（毫秒）：首轮等发布方提交，后面几轮覆盖较长的事务。 */
    private val settleDelaysMillis: List<Long> = DEFAULT_SETTLE_DELAYS_MILLIS,
    private val onError: (Throwable) -> Unit = {},
) {
    init {
        require(settleDelaysMillis.isNotEmpty() && settleDelaysMillis.all { it >= 0 }) { "settleDelaysMillis must be non-empty and non-negative" }
    }

    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /** 有新记录写入。不挂起、不阻塞发布方；未启动时什么都不做（记录照样由周期任务投递）。 */
    fun signal() {
        wakeups.trySend(Unit)
    }

    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
        check(job == null) { "PromptEventDispatch already started" }
        job = scope.launch {
            while (true) {
                wakeups.receive()
                drain()
            }
        }
    }

    suspend fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * 按节奏试几轮。途中又有唤醒：那条新记录也要先等它的事务提交，于是从第一档重新开始节奏——
     * 但每次重新开始都**照样投递一轮**，持续写入时投递按第一档的间隔进行，不会被不断推迟（不饥饿）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun drain() {
        var i = 0
        while (i < settleDelaysMillis.size) {
            val woken = select {
                wakeups.onReceive { true }
                onTimeout(settleDelaysMillis[i]) { false }
            }
            if (woken) {
                delay(settleDelaysMillis[0])
                i = 0
            }
            try {
                dispatchOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(e)
            }
            i++
        }
    }

    companion object {
        /** 50 ms 后、再 250 ms、再 1 s、再 3 s：覆盖到 4.3 秒内提交的事务，之后交给周期任务。 */
        val DEFAULT_SETTLE_DELAYS_MILLIS: List<Long> = listOf(50, 250, 1_000, 3_000)
    }
}
