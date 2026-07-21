package controller.admin.log

import logic.ApiErrorLogLogic
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Put
import neton.core.annotations.Permission
import neton.core.annotations.Query
import neton.core.interfaces.Identity

@Controller("/infra/api-error-log")
class ApiErrorLogController(
    private val logLogic: ApiErrorLogLogic
) {

    @Get("/page")
    @Permission("infra:api-error-log:query")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 10,
        @Query userId: Long? = null,
        @Query applicationName: String? = null,
        @Query requestUrl: String? = null,
        @Query processStatus: Int? = null
    ) = logLogic.page(pageNo, pageSize, userId, applicationName, requestUrl, processStatus)

    @Get("/get/{id}")
    @Permission("infra:api-error-log:query")
    suspend fun get(@PathVariable id: Long) = logLogic.getById(id)

    @Put("/update-status/{id}")
    @Permission("infra:api-error-log:update")
    suspend fun updateStatus(
        @PathVariable id: Long,
        @Query processStatus: Int,
        identity: Identity
    ) {
        logLogic.updateStatus(id, processStatus, identity.id.toLong())
    }
}
