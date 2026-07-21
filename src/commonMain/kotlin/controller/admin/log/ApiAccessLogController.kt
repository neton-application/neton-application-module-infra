package controller.admin.log

import controller.admin.log.dto.ApiAccessLogVO
import dto.PageResponse
import model.ApiAccessLog
import table.ApiAccessLogTable
import neton.database.dsl.*

import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Permission
import neton.core.annotations.Query
import neton.logging.Logger

@Controller("/infra/api-access-log")
class ApiAccessLogController(
    private val log: Logger
) {

    @Get("/page")
    @Permission("infra:api-access-log:query")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 10,
        @Query userId: Long? = null,
        @Query applicationName: String? = null,
        @Query requestUrl: String? = null,
        @Query resultCode: Int? = null
    ): PageResponse<ApiAccessLogVO> {
        val result = ApiAccessLogTable.query {
            where {
                and(
                    whenPresent(userId) { ApiAccessLog::userId eq it },
                    whenNotBlank(applicationName) { ApiAccessLog::applicationName like "%$it%" },
                    whenNotBlank(requestUrl) { ApiAccessLog::requestUrl like "%$it%" },
                    whenPresent(resultCode) { ApiAccessLog::resultCode eq it }
                )
            }
            orderBy(ApiAccessLog::id.desc())
        }.page(pageNo, pageSize)

        return PageResponse(
            list = result.items.map { it.toVO() },
            total = result.total,
            page = pageNo,
            size = pageSize,
            totalPages = if (pageSize > 0) ((result.total + pageSize - 1) / pageSize).toInt() else 0
        )
    }

    @Get("/get/{id}")
    @Permission("infra:api-access-log:query")
    suspend fun get(@PathVariable id: Long): ApiAccessLog? {
        return ApiAccessLogTable.get(id)
    }

    private fun ApiAccessLog.toVO() = ApiAccessLogVO(
        id = id,
        userId = userId,
        requestMethod = requestMethod,
        requestUrl = requestUrl,
        duration = duration,
        resultCode = resultCode,
        createdAt = createdAt
    )
}
