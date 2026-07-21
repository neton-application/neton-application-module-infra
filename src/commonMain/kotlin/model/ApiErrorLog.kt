package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.CreatedAt

@Serializable
@Table("infra_api_error_logs")
data class ApiErrorLog(
    @Id
    val id: Long = 0,
    val userId: Long? = null,
    val userType: Int = 0,
    val applicationName: String? = null,
    val requestMethod: String? = null,
    val requestUrl: String,
    val requestParams: String? = null,
    val userIp: String? = null,
    val userAgent: String? = null,
    val exceptionName: String? = null,
    val exceptionMessage: String? = null,
    val exceptionStackTrace: String? = null,
    val processStatus: Int = 0,
    val processUserId: Long? = null,
    val processTime: Long? = null,
    @CreatedAt
    val createdAt: String? = null
)
