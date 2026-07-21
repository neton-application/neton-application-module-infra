package logic

import dto.PageResponse
import model.Job
import model.JobLog
import table.JobTable
import table.JobLogTable
import neton.database.dsl.*

import neton.logging.Logger

@neton.core.annotations.Logic(logger = "logic.job")
class JobLogic(
    private val log: Logger
) {

    // --- Job operations ---

    suspend fun createJob(job: Job): Long {
        val inserted = JobTable.insert(job)
        log.info("Created job with id: ${inserted.id}, name: ${job.name}")
        return inserted.id
    }

    suspend fun updateJob(job: Job) {
        JobTable.update(job)
        log.info("Updated job with id: ${job.id}")
    }

    suspend fun deleteJob(id: Long) {
        JobTable.destroy(id)
        log.info("Deleted job with id: $id")
    }

    suspend fun getJob(id: Long): Job? {
        return JobTable.get(id)
    }

    suspend fun updateJobStatus(id: Long, status: Int) {
        val job = JobTable.get(id) ?: return
        JobTable.update(job.copy(status = status))
        log.info("Updated job id: $id status to: $status")
    }

    suspend fun triggerJob(id: Long) {
        val job = JobTable.get(id) ?: return
        log.info("Triggered job id: $id, name: ${job.name}")
    }

    suspend fun getJobNextTimes(id: Long): List<String> {
        val job = JobTable.get(id) ?: return emptyList()
        log.info("Getting next execution times for job id: $id, cron: ${job.cronExpression}")
        return emptyList()
    }

    suspend fun pageJob(
        page: Int,
        size: Int,
        name: String? = null,
        status: Int? = null,
        handlerName: String? = null
    ): PageResponse<Job> {
        val result = JobTable.query {
            where {
                and(
                    whenNotBlank(name) { Job::name like "%$it%" },
                    whenPresent(status) { Job::status eq it },
                    whenNotBlank(handlerName) { Job::handlerName like "%$it%" }
                )
            }
            orderBy(Job::id.desc())
        }.page(page, size)
        return PageResponse(result.items, result.total, page, size,
            if (size > 0) ((result.total + size - 1) / size).toInt() else 0)
    }

    // --- JobLog operations ---

    suspend fun getJobLog(id: Long): JobLog? {
        return JobLogTable.get(id)
    }

    suspend fun pageJobLog(
        page: Int,
        size: Int,
        jobId: Long? = null,
        handlerName: String? = null,
        status: Int? = null
    ): PageResponse<JobLog> {
        val result = JobLogTable.query {
            where {
                and(
                    whenPresent(jobId) { JobLog::jobId eq it },
                    whenNotBlank(handlerName) { JobLog::handlerName like "%$it%" },
                    whenPresent(status) { JobLog::status eq it }
                )
            }
            orderBy(JobLog::id.desc())
        }.page(page, size)
        return PageResponse(result.items, result.total, page, size,
            if (size > 0) ((result.total + size - 1) / size).toInt() else 0)
    }
}
