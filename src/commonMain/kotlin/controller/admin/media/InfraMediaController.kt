package controller.admin.media

import controller.app.infra.file.dto.FileUploadResponse
import logic.FileUploadLogic
import logic.MediaLogic
import logic.MediaRules
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.annotations.RateLimit
import neton.core.annotations.RateLimitScope
import neton.core.http.BadRequestException
import neton.core.http.HttpException
import neton.core.http.HttpRequest
import neton.core.http.HttpResponse
import neton.core.http.NetonErrorCode
import neton.core.http.NotFoundException
import neton.core.http.bytes
import neton.core.interfaces.Identity

/**
 * 后台媒体入口（infra-media §4.1「后台上传同理」、§9），挂在 `/admin/infra/media/…`。
 *
 * 为什么不放在 `/admin/infra/file/…`：那里的 `POST /upload`（只登记元数据）与 `GET /get/{id}`
 * （按自增 id 返回元数据 JSON）是已有接口，各宿主在用，语义不能改，同组同 pattern 也注册不了第二条。
 *
 * - `POST /upload`：multipart `file` + `businessType`，与 `POST /app/infra/file/upload` 同一套
 *   策略与落盘路径；托管类型以 temporary 起步。上传者记为后台账号（`admin_uploader_id`），
 *   业务模块的后台保存路径以 `MediaService.attach(…, actorUserId = null)` 关联。
 * - `GET /get/{fileId}`：读取任意未删除文件的字节（含临时、草稿、会员可见文件），`private, no-store`。
 *   地址由 `MediaService.resolveForAdmin` 下发，凭后台会话 Cookie 读取。
 */
@Controller("/infra/media")
class InfraMediaController(
    private val files: FileUploadLogic,
    private val media: MediaLogic,
) {

    @Post("/upload")
    @Permission("infra:media:upload")
    @RateLimit(
        windowSeconds = 60,
        maxRequests = 60,
        scope = RateLimitScope.USER,
        message = "Upload rate limit exceeded, please slow down",
    )
    suspend fun upload(identity: Identity, request: HttpRequest): FileUploadResponse {
        if (!request.isMultipart()) {
            throw HttpException(
                code = NetonErrorCode.INVALID_PARAM_TYPE,
                message = "INVALID_CONTENT_TYPE: expected multipart/form-data",
            )
        }
        val file = request.uploadFiles().first("file")
            ?: throw BadRequestException("MISSING_FILE_FIELD: form field 'file' is required")
        val businessType = request.form()["businessType"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw BadRequestException("MISSING_BUSINESS_TYPE: form field 'businessType' is required")
        val adminUserId = identity.id.toLongOrNull()
            ?: throw HttpException(NetonErrorCode.PERMISSION_DENIED, "INVALID_ADMIN_IDENTITY")

        val record = files.uploadAsAdmin(adminUserId, businessType, file)
        return FileUploadResponse(
            fileId = record.fileId
                ?: error("FileUploadLogic.uploadAsAdmin returned record without fileId; logic invariant broken"),
            // 非公开文件给后台读取地址，编辑器上传后即可预览
            url = media.adminUrlOf(record),
            businessType = record.businessType ?: "",
            mimeType = record.mimeType ?: "application/octet-stream",
            size = record.size,
        )
    }

    @Get("/get/{fileId}")
    @Permission("infra:media:query")
    suspend fun get(response: HttpResponse, @PathVariable fileId: String) {
        val record = files.getByFileId(fileId) ?: throw NotFoundException("FILE_NOT_FOUND")
        if (!media.isAdminReadable(record)) throw NotFoundException("FILE_DELETED")

        val bytes = files.readBytes(record)
        val mime = record.mimeType ?: "application/octet-stream"
        // 后台读取不进任何缓存：文件可能是草稿 / 会员可见内容，且随时会被清理
        response.header("Cache-Control", "private, no-store")
        // 用户上传的内容在后台源下打开：禁止嗅探、禁止脚本，可执行类型一律按附件下载
        response.header("X-Content-Type-Options", "nosniff")
        response.header("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'; sandbox")
        if (!MediaRules.inlineSafe(mime)) {
            response.header("Content-Disposition", "attachment")
        }
        response.bytes(data = bytes, contentType = mime)
    }
}
