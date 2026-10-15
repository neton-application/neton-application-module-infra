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
    val createdAt: Long? = null
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

/**
 * 后台文件分页的一行：`infra_files` 全部列 + 媒体引用数（infra-media §9）。
 * 字段是旧响应（直接返回 FileInfo）的超集，前端不改也能用。
 */
@Serializable
data class FileInfoPageItemVO(
    val id: Long,
    val fileId: String? = null,
    val ownerUid: Long? = null,
    /** 后台上传者（system_users.id）；应用账号上传的行为空。 */
    val adminUploaderId: Long? = null,
    val businessType: String? = null,
    val configId: Long? = null,
    val name: String,
    val path: String,
    val url: String? = null,
    val mimeType: String? = null,
    val size: Long = 0,
    val sha256: String? = null,
    val status: Int = 1,
    val lifecycle: String = "attached",
    val lifecycleAt: Long? = null,
    val purgedAt: Long? = null,
    val referenceCount: Long = 0,
    val createdAt: Long? = null,
)
