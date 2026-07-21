package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.CreatedAt
import neton.database.annotations.UpdatedAt

@Serializable
@Table("infra_configs")
data class Config(
    @Id
    val id: Long = 0,
    val category: String,
    val configKey: String,
    val value: String,
    val type: Int = 0,
    val name: String,
    val remark: String? = null,
    @CreatedAt
    val createdAt: String? = null,
    @UpdatedAt
    val updatedAt: String? = null
)
