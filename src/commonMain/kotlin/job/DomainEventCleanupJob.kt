package job

import event.DomainEventSettings
import logic.DomainEventOpsLogic
import neton.core.component.NetonContext
import neton.jobs.ExecutionMode
import neton.jobs.Job
import neton.jobs.JobContext
import neton.jobs.JobExecutor
import neton.logging.Logger

/**
 * 清理已投递 / 已丢弃且超过保留期的 outbox 记录。
 *
 * `SINGLE_NODE`：清理不需要并行，重复执行也无害，正是分布式"尽力锁"的适用场景；
 * 与投递任务用 `ALL_NODES` 形成对照——那边靠数据库领取互斥，这边只是不想让 N 个节点同时扫表。
 */
@Job(
    id = "infra-domain-event-cleanup",
    cron = "17 3 * * *",
    mode = ExecutionMode.SINGLE_NODE,
)
class DomainEventCleanupJob(
    private val log: Logger,
    private val ctx: NetonContext,
) : JobExecutor {

    override suspend fun run(context: JobContext) {
        val ops = ctx.getOrNull(DomainEventOpsLogic::class) ?: return
        val settings = DomainEventSettings.load()
        val retentionMillis = settings.retentionDays.toLong() * 24 * 60 * 60 * 1000
        val deleted = ops.cleanup(retentionMillis)
        if (deleted > 0) log.info("event.cleanup", mapOf("deleted" to deleted, "retentionDays" to settings.retentionDays))
    }
}
