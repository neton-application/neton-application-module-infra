package infra

import model.ApiAccessLog
import table.ApiAccessLogTable
import neton.core.interfaces.AccessLogEntry
import neton.core.interfaces.AccessLogWriter

/**
 * 将 API 访问日志持久化到数据库
 */
class DbAccessLogWriter : AccessLogWriter {

    override suspend fun write(entry: AccessLogEntry) {
        val log = ApiAccessLog(
            userId = entry.userId,
            userType = entry.userType,
            applicationName = entry.applicationName,
            requestMethod = entry.requestMethod,
            requestUrl = entry.requestUrl,
            requestParams = entry.requestParams,
            userIp = entry.userIp,
            userAgent = entry.userAgent,
            beginTime = entry.beginTime,
            endTime = entry.endTime,
            duration = entry.duration,
            resultCode = entry.resultCode,
            resultMsg = entry.resultMsg
        )
        ApiAccessLogTable.insert(log)
    }
}
