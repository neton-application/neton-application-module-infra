package controller.admin.file.dto

import kotlinx.serialization.Serializable

@Serializable
data class FilePresignedUrlVO(
    val configId: Long,
    val uploadUrl: String,
    val url: String,
    val path: String
)
