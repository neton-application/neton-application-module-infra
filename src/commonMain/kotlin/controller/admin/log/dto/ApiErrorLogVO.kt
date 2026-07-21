package controller.admin.log.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorLogVO(
    val id: Long = 0,
    val userId: Long? = null,
    val requestMethod: String? = null,
    val requestUrl: String? = null,
    val exceptionName: String? = null,
    val processStatus: Int = 0,
    val createdAt: String? = null
)
