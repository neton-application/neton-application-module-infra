package logic

import controller.admin.log.dto.ApiErrorLogVO
import dto.PageResponse
import model.ApiErrorLog
import table.ApiErrorLogTable
import neton.core.http.NotFoundException
import neton.database.dsl.*

import neton.logging.Logger
import kotlin.time.Clock

class ApiErrorLogLogic(
    private val log: Logger
) {

    suspend fun page(
        page: Int,
        size: Int,
        userId: Long? = null,
        applicationName: String? = null,
        requestUrl: String? = null,
        processStatus: Int? = null
    ): PageResponse<ApiErrorLogVO> {
        val result = ApiErrorLogTable.query {
            where {
                and(
                    whenPresent(userId) { ApiErrorLog::userId eq it },
                    whenNotBlank(applicationName) { ApiErrorLog::applicationName like "%$it%" },
                    whenNotBlank(requestUrl) { ApiErrorLog::requestUrl like "%$it%" },
                    whenPresent(processStatus) { ApiErrorLog::processStatus eq it }
                )
            }
            orderBy(ApiErrorLog::id.desc())
        }.page(page, size)

        return PageResponse(
            list = result.items.map { it.toVO() },
            total = result.total,
            page = page,
            size = size,
            totalPages = if (size > 0) ((result.total + size - 1) / size).toInt() else 0
        )
    }

    suspend fun getById(id: Long): ApiErrorLog? {
        return ApiErrorLogTable.get(id)
    }

    suspend fun updateStatus(id: Long, processStatus: Int, processUserId: Long) {
        val errorLog = ApiErrorLogTable.get(id)
            ?: throw NotFoundException("Error log not found")
        val updated = errorLog.copy(
            processStatus = processStatus,
            processUserId = processUserId,
            processTime = Clock.System.now().toEpochMilliseconds()
        )
        ApiErrorLogTable.update(updated)
    }

    private fun ApiErrorLog.toVO() = ApiErrorLogVO(
        id = id,
        userId = userId,
        requestMethod = requestMethod,
        requestUrl = requestUrl,
        exceptionName = exceptionName,
        processStatus = processStatus,
        createdAt = createdAt
    )
}
