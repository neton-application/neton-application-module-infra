package controller.app.infra.file

import controller.app.infra.file.dto.FileUploadResponse
import kotlinx.serialization.Serializable
import logic.FileUploadLogic
import logic.MediaLogic
import neton.core.annotations.AllowAnonymous
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Post
import neton.core.annotations.Query
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
 * 应用唯一用户态文件入口（spec MODULE_MEMBER_PROFILE_SPEC §4.2）。
 *
 * 包路径 `controller.app.infra.file` 让 neton 路由组（按包段匹配 `[[groups]]`，
 * 详 `KtorHttpAdapter.inferRouteGroup`）落到 `app` group，最终挂在
 * `/app/infra/file/<path>`。与 `controller.admin.file.FileController`
 * （admin 元数据登记，挂在 `/admin/infra/file/<path>`）共存，互不干扰。
 *
 * 端点：
 * - `POST /upload` 接 multipart/form-data file 字节 + businessType
 * - `GET  /get/<fileId>` 按 fileId 取文件字节（鉴权见 spec §4.2）
 */
@Controller("/infra/file")
class FileController(
    private val fileLogic: FileUploadLogic,
    private val media: MediaLogic,
) {

    /**
     * 应用唯一用户态文件上传入口。
     *
     * - `Content-Type: multipart/form-data`
     * - 必传字段 `file`（文件字节）+ `businessType`（policy registry 识别）
     * - 业务规则、MIME / size 校验全在 [FileUploadLogic] 内（不在 controller）
     * - 频控：每用户 60 秒 30 次（足够正常上传 + 防滥用）
     */
    @Post("/upload")
    @RateLimit(
        windowSeconds = 60,
        maxRequests = 30,
        scope = RateLimitScope.USER,
        message = "Upload rate limit exceeded, please slow down",
    )
    suspend fun upload(
        identity: Identity,
        request: HttpRequest,
    ): FileUploadResponse {
        if (!request.isMultipart()) {
            throw HttpException(
                code = NetonErrorCode.INVALID_PARAM_TYPE,
                message = "INVALID_CONTENT_TYPE: expected multipart/form-data",
            )
        }
        val files = request.uploadFiles()
        val file = files.first("file")
            ?: throw BadRequestException("MISSING_FILE_FIELD: form field 'file' is required")

        val form = request.form()
        val businessType = form["businessType"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw BadRequestException("MISSING_BUSINESS_TYPE: form field 'businessType' is required")

        val record = fileLogic.upload(
            ownerUid = identity.id.toLong(),
            businessType = businessType,
            file = file,
        )
        return FileUploadResponse(
            fileId = record.fileId
                ?: error("FileUploadLogic.upload returned record without fileId; logic invariant broken"),
            url = record.url ?: "",
            businessType = record.businessType ?: "",
            mimeType = record.mimeType ?: "application/octet-stream",
            size = record.size,
        )
    }

    /**
     * 按 fileId 取文件字节。
     *
     * - PUBLIC（avatar / generic_image）**匿名可读**——头像经 <img>/Image 标签
     *   加载,带不了 Authorization header;内容寻址 fileId 本身即不可猜的凭据
     * - OWNER（feedback / kyc，旧称 PRIVATE）仅 owner 可读(匿名一律拒)
     * - DELEGATED：任一引用对象对访问者可见即可读（infra-media §7.1）；无引用的临时文件只有上传者可读
     * - 带有效未过期签名（`?exp=&sig=`，由 `MediaService.resolve` 签发）→ 不看身份直接下发
     * - 不存在 → 404；已删除 / 无权 → 403
     */
    @AllowAnonymous
    @Get("/get/{fileId}")
    suspend fun get(
        identity: Identity?,
        response: HttpResponse,
        @PathVariable fileId: String,
        @Query exp: Long? = null,
        @Query sig: String? = null,
    ) {
        val record = fileLogic.getByFileId(fileId)
            ?: throw NotFoundException("FILE_NOT_FOUND: $fileId")

        val cacheControl: String
        if (record.status == 1 && media.verifySignature(fileId, exp, sig)) {
            // 签名地址：签发时已确认可读；缓存不超过签名剩余有效期
            cacheControl = "private, max-age=${media.signatureRemainingSeconds(exp!!)}"
        } else {
            val viewerUid = identity?.id?.toLongOrNull()
            if (!media.canRead(record, viewerUid)) {
                throw HttpException(
                    code = NetonErrorCode.PERMISSION_DENIED,
                    message = "FILE_FORBIDDEN: $fileId",
                )
            }
            cacheControl = if (!media.isDelegated(record)) {
                // 内容寻址(fileId 由 SHA-256 派生,内容变即换 URL)⇒ 永久缓存安全;
                // 浏览器 HTTP 缓存借此达到微信式头像缓存等效(AVATAR_CACHE_SPEC §2)。
                "public, max-age=31536000, immutable"
            } else {
                // 委托访问随引用对象状态变化，不能被共享缓存或长期缓存
                "private, no-store"
            }
        }

        val bytes = fileLogic.readBytes(record)
        response.header("Cache-Control", cacheControl)
        response.bytes(
            data = bytes,
            contentType = record.mimeType ?: "application/octet-stream",
        )
    }
}

