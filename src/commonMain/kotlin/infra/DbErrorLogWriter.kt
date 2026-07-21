package infra

import model.ApiErrorLog
import table.ApiErrorLogTable
import neton.core.interfaces.ErrorLogEntry
import neton.core.interfaces.ErrorLogWriter

/**
 * 将 API 错误日志持久化到数据库
 */
class DbErrorLogWriter : ErrorLogWriter {

    override suspend fun write(entry: ErrorLogEntry) {
        val log = ApiErrorLog(
            userId = entry.userId,
            userType = entry.userType,
            applicationName = entry.applicationName,
            requestMethod = entry.requestMethod,
            requestUrl = entry.requestUrl,
            requestParams = entry.requestParams,
            userIp = entry.userIp,
            userAgent = entry.userAgent,
            exceptionName = entry.exceptionName,
            exceptionMessage = entry.exceptionMessage,
            exceptionStackTrace = entry.exceptionStackTrace
        )
        ApiErrorLogTable.insert(log)
    }
}
