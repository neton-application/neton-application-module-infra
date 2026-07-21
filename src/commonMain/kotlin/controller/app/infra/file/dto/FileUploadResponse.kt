package controller.app.infra.file.dto

import kotlinx.serialization.Serializable

/**
 * `POST /app/infra/file/upload` 响应（spec MODULE_MEMBER_PROFILE_SPEC §4.2）。
 *
 * 客户端拿 [fileId] 走后续业务（如 `update-avatar { fileId }`），不要保存 [url]
 * 当唯一引用——url 形态可能在后续 PR-D2 image processing 上线时变化（多变体 URL）。
 */
@Serializable
data class FileUploadResponse(
    val fileId: String,
    val url: String,
    val businessType: String,
    val mimeType: String,
    val size: Long,
)
