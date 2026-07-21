package logic

import model.Job
import model.JobLog
import table.JobTable
import table.JobLogTable
import neton.jobs.JobConfigSource
import neton.jobs.JobExecutionListener
import neton.jobs.JobRegistry
import neton.jobs.JobSchedule
import neton.database.dsl.*
import neton.logging.Logger

/**
 * DB 驱动的任务配置源（RP-7-B1）。让后台 `infra_jobs` 成为运行期调度真源：
 * 代码 `@Job` 定义 handler 能力，DB 决定启用/停用。
 *
 * `prepare` 阶段被 JobsComponent 调用一次：
 * 1. **upsert**：代码里已注册（JobRegistry）但 DB 缺失的任务，插一条默认系统任务（handler_name=@Job.id），后台可见。
 * 2. **override**：读 infra_jobs，对已注册 handler 的行返回覆盖（enabled 来自 status；cron 来自 cronExpression）。
 *
 * v1：DB 只覆盖 enabled(+cron)；fixedRate 等调度参数用代码 @Job 默认（infra_jobs 暂无 fixed_rate 列，后补）。
 */
class DbBackedJobConfigSource(
    private val log: Logger,
    private val registryProvider: () -> JobRegistry,
) : JobConfigSource {
    override suspend fun overrides(): List<Map<String, Any?>> {
        val codeJobs = registryProvider().jobs
        // 1. upsert 缺失的代码任务 → infra_jobs（后台可见）
        for (def in codeJobs) {
            val existing = JobTable.oneWhere { Job::handlerName eq def.id }
            if (existing == null) {
                val cron = (def.schedule as? JobSchedule.Cron)?.expression
                JobTable.insert(
                    Job(
                        name = def.id,
                        handlerName = def.id,
                        cronExpression = cron,
                        status = if (def.enabled) 1 else 0,
                    )
                )
                log.info("job.registry.upsert", mapOf("handler" to def.id, "enabled" to def.enabled))
            }
        }
        // 2. 读 infra_jobs → 覆盖（只对已注册 handler 生效）
        val codeIds = codeJobs.map { it.id }.toSet()
        return JobTable.query { }.list()
            .filter { it.handlerName in codeIds }
            .map { row ->
                buildMap<String, Any?> {
                    put("id", row.handlerName)
                    put("enabled", row.status == 1)
                    row.cronExpression?.takeIf { it.isNotBlank() }?.let { put("cron", it) }
                }
            }
    }
}

/**
 * 任务执行日志监听器（RP-7-B1）。把 neton-jobs 的执行回调落到 `infra_job_logs`，后台执行日志页可查。
 * onStart 插一条 running 行（按 handlerName+beginTime 定位）；onSuccess/onFailure 回填 end/duration/status/result。
 * status：0 运行中 / 1 成功 / 2 失败。
 */
class JobLogListener(private val log: Logger) : JobExecutionListener {
    override suspend fun onStart(jobId: String, fireTime: Long) {
        val infraId = JobTable.oneWhere { Job::handlerName eq jobId }?.id ?: 0L
        JobLogTable.insert(JobLog(jobId = infraId, handlerName = jobId, beginTime = fireTime, status = 0))
    }

    override suspend fun onSuccess(jobId: String, fireTime: Long, duration: Long) =
        finish(jobId, fireTime, duration, status = 1, msg = "success")

    override suspend fun onFailure(jobId: String, fireTime: Long, duration: Long, error: Throwable) =
        finish(jobId, fireTime, duration, status = 2, msg = (error.message ?: error::class.simpleName ?: "error").take(500))

    private suspend fun finish(jobId: String, fireTime: Long, duration: Long, status: Int, msg: String) {
        val updated = JobLogTable.query {
            where { and(JobLog::handlerName eq jobId, JobLog::beginTime eq fireTime) }
        }.update {
            set(JobLog::endTime, fireTime + duration)
            set(JobLog::duration, duration)
            set(JobLog::status, status)
            set(JobLog::resultMsg, msg)
        }
        if (updated == 0L) log.warn("job.log.finish.miss", mapOf("handler" to jobId, "fireTime" to fireTime))
    }
}
