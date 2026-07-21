package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.CreatedAt

@Serializable
@Table("infra_job_logs")
data class JobLog(
    @Id
    val id: Long = 0,
    val jobId: Long,
    val handlerName: String,
    val handlerParam: String? = null,
    val executeIndex: Int = 1,
    val beginTime: Long = 0,
    val endTime: Long? = null,
    val duration: Long? = null,
    val status: Int = 0,
    val resultMsg: String? = null,
    @CreatedAt
    val createdAt: String? = null
)
