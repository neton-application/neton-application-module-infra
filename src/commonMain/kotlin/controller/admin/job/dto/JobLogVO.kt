package controller.admin.job.dto

import kotlinx.serialization.Serializable

@Serializable
data class JobLogVO(
    val id: Long = 0,
    val jobId: Long = 0,
    val handlerName: String? = null,
    val status: Int? = null,
    val duration: Long? = null,
    val beginTime: Long = 0,
    val endTime: Long? = null,
    val resultMsg: String? = null
)
