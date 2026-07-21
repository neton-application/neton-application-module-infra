package controller.admin.file.dto

import kotlinx.serialization.Serializable
import neton.validation.annotations.Min
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Size

@Serializable
data class FileInfoVO(
    val id: Long = 0,
    val configId: Long? = null,
    val name: String? = null,
    val path: String? = null,
    val url: String? = null,
    val mimeType: String? = null,
    val size: Long = 0,
    val createdAt: String? = null
)

@Serializable
data class UploadFileRequest(
    @property:Min(1)
    val configId: Long,

    @property:NotBlank
    @property:Size(min = 1, max = 255)
    val name: String,

    @property:NotBlank
    @property:Size(min = 1, max = 500)
    val path: String,

    @property:Size(min = 0, max = 1000)
    val url: String? = null,

    @property:Size(min = 0, max = 255)
    val mimeType: String? = null,

    @property:Min(0)
    val size: Long = 0
)
