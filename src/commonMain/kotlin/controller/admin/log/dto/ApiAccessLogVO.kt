package controller.admin.log.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiAccessLogVO(
    val id: Long = 0,
    val userId: Long? = null,
    val requestMethod: String? = null,
    val requestUrl: String? = null,
    val duration: Long = 0,
    val resultCode: Int = 0,
    val createdAt: String? = null
)
