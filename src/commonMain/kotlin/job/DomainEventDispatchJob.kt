package job

import event.DomainEventDispatcher
import neton.core.component.NetonContext
import neton.core.event.DomainEventBus
import neton.core.event.DomainEventCodec
import neton.core.event.DomainEventStore
import neton.jobs.ExecutionMode
import neton.jobs.Job
import neton.jobs.JobContext
import neton.jobs.JobExecutor
import neton.logging.Logger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 投递已落库的事件。
 *
 * 只有声明为持久化投递的监听者才会走这里；同步与尽力而为两种模式在发布时就执行完了。
 *
 * 允许多节点并行：领取用条件更新占位，同一条不会被两个节点同时取走，
 * 因此这里不设单节点限制 —— 积压时多个实例一起消费能更快追平。
 */
@OptIn(ExperimentalTime::class)
@Job(
    id = "infra-domain-event-dispatch",
    fixedRate = 10_000,
    initialDelay = 10_000,
    mode = ExecutionMode.ALL_NODES,
)
class DomainEventDispatchJob(
    private val log: Logger,
    private val ctx: NetonContext,
) : JobExecutor {

    override suspend fun run(context: JobContext) {
        val store = ctx.getOrNull(DomainEventStore::class) ?: return
        val bus = ctx.getOrNull(DomainEventBus::class) ?: return
        // 编解码由应用层提供；未提供说明本部署没有使用持久化投递
        val codec = ctx.getOrNull(DomainEventCodec::class) ?: return

        val dispatcher = DomainEventDispatcher(
            store = store,
            bus = bus,
            codec = codec,
            onError = { failure ->
                if (failure.permanent) {
                    log.error("event.dispatch.permanent_failure id=${failure.id} listener=${failure.listenerId} reason=${failure.reason}")
                } else {
                    log.warn("event.dispatch.retry id=${failure.id} listener=${failure.listenerId} reason=${failure.reason}")
                }
            },
        )
        val delivered = dispatcher.dispatchOnce(Clock.System.now().toEpochMilliseconds())
        if (delivered > 0) log.info("event.dispatch delivered=$delivered")
    }
}
