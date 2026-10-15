package job

import logic.MediaLogic
import neton.core.component.NetonContext
import neton.jobs.ExecutionMode
import neton.jobs.Job
import neton.jobs.JobContext
import neton.jobs.JobExecutor
import neton.logging.Logger

/**
 * 清理过期的托管媒体（infra-media §6）：temporary 超过 temporaryHours、orphaned 超过 orphanedDays。
 *
 * `SINGLE_NODE` 只是为了不让 N 个节点同时扫表；正确性不依赖它——每个文件都在行锁内重新确认
 * 状态与引用数，物理删除在路径咨询锁内确认无共用（见 [MediaLogic.cleanupExpired]）。
 * 只处理 `managedLifecycle` 的业务类型，内置类型永远不进来。
 */
@Job(
    id = "infra-media-cleanup",
    cron = "41 * * * *",
    mode = ExecutionMode.SINGLE_NODE,
    lockTtlMs = 10 * 60_000,
)
class MediaCleanupJob(
    private val log: Logger,
    private val ctx: NetonContext,
) : JobExecutor {

    override suspend fun run(context: JobContext) {
        val media = ctx.getOrNull(MediaLogic::class) ?: return
        val report = media.cleanupExpired()
        if (report.purgeFailed > 0) {
            log.warn("media.cleanup.purge-failed", mapOf("count" to report.purgeFailed))
        }
    }
}
