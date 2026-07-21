package controller.admin.job

import logic.JobLogic
import model.JobLog
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Permission
import neton.core.annotations.Query
import neton.core.annotations.PathVariable
import neton.logging.Logger

@Controller("/infra/job-log")
class JobLogController(
    private val log: Logger,
    private val jobLogic: JobLogic = JobLogic(log)
) {

    @Get("/page")
    @Permission("infra:job-log:query")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 10,
        @Query jobId: Long? = null,
        @Query handlerName: String? = null,
        @Query status: Int? = null
    ) = jobLogic.pageJobLog(pageNo, pageSize, jobId, handlerName, status)

    @Get("/get/{id}")
    @Permission("infra:job-log:query")
    suspend fun get(@PathVariable id: Long): JobLog? {
        return jobLogic.getJobLog(id)
    }
}
