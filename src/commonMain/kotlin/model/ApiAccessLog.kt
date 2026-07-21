package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.CreatedAt

@Serializable
@Table("infra_api_access_logs")
data class ApiAccessLog(
    @Id
    val id: Long = 0,
    val userId: Long? = null,
    val userType: Int = 0,
    val applicationName: String? = null,
    val requestMethod: String? = null,
    val requestUrl: String,
    val requestParams: String? = null,
    val responseBody: String? = null,
    val userIp: String? = null,
    val userAgent: String? = null,
    val operateModule: String? = null,
    val operateName: String? = null,
    val operateType: Int = 0,
    val beginTime: Long = 0,
    val endTime: Long = 0,
    val duration: Long = 0,
    val resultCode: Int = 0,
    val resultMsg: String? = null,
    @CreatedAt
    val createdAt: String? = null
)
