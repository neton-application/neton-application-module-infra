package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.CreatedAt
import neton.database.annotations.UpdatedAt

@Serializable
@Table("infra_jobs")
data class Job(
    @Id
    val id: Long = 0,
    val name: String,
    val handlerName: String,
    val handlerParam: String? = null,
    val cronExpression: String? = null,
    val retryCount: Int = 0,
    val retryInterval: Int = 0,
    val status: Int = 1,
    @CreatedAt
    val createdAt: String? = null,
    @UpdatedAt
    val updatedAt: String? = null
)
