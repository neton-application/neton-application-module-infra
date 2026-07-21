package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.CreatedAt
import neton.database.annotations.UpdatedAt

@Serializable
@Table("infra_file_configs")
data class FileConfig(
    @Id
    val id: Long = 0,
    val name: String,
    val storage: Int,
    val config: String,
    val master: Int = 0,
    val remark: String? = null,
    @CreatedAt
    val createdAt: String? = null,
    @UpdatedAt
    val updatedAt: String? = null
)
