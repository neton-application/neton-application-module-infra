package job

import event.DomainEventDispatcher
import event.DomainEventSettings
import logic.DomainEventOpsLogic
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

        val settings = DomainEventSettings.load()
        val dispatcher = DomainEventDispatcher(
            store = store,
            bus = bus,
            codec = codec,
            config = settings.dispatch,
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

        // 积压告警：这是 outbox 唯一主动发出的健康信号。终态失败、待投递堆积、
        // 最老记录年龄过大，都说明有人该看一眼——而没有这条，他们只会在客诉里发现。
        val ops = ctx.getOrNull(DomainEventOpsLogic::class) ?: return
        val stats = ops.stats()
        val oldestAge = stats.oldestPendingAgeMillis ?: 0L
        val unhealthy = stats.failed > 0 ||
            stats.pending > settings.backlogWarnPending ||
            oldestAge > settings.backlogWarnAgeMillis
        if (unhealthy) {
            log.warn(
                "event.backlog",
                mapOf(
                    "pending" to stats.pending,
                    "delivering" to stats.delivering,
                    "failed" to stats.failed,
                    "oldestPendingAgeMs" to stats.oldestPendingAgeMillis,
                    "oldestDeliveringAgeMs" to stats.oldestDeliveringAgeMillis,
                ),
            )
        }
    }
}
