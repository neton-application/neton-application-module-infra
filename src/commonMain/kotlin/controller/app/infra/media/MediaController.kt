package controller.app.infra.media

import kotlinx.serialization.Serializable
import logic.MediaLogic
import logic.MediaVariant
import neton.core.annotations.AllowAnonymous
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Query
import neton.core.annotations.RateLimit
import neton.core.annotations.RateLimitScope
import neton.core.http.BadRequestException
import neton.core.http.HttpContext
import neton.core.http.NotFoundException
import neton.core.http.ValidationError
import neton.core.interfaces.Identity
import port.InfraErrorCodes

/** `GET /app/infra/media/policy` 的响应：当前部署的 CDN 地址策略（infra-media §7.3）。 */
@Serializable
data class MediaUrlPolicyVO(
    val strategy: String,
    /** 公开 HTML 里媒体地址的有效期（秒）；永不过期为 null。 */
    val validitySeconds: Long? = null,
    /** 签名时间桶（秒）；不按桶签名为 null。 */
    val bucketSeconds: Long? = null,
    /** 图片处理参数方言：tencent / aliyun / none。 */
    val imageParams: String,
)

/** `GET /app/infra/media/url/{fileId}` 的响应：当前桶的新地址。 */
@Serializable
data class MediaUrlVO(
    val url: String,
    /** epoch 秒；不过期为 null。 */
    val expiresAt: Long? = null,
)

/**
 * 公开媒体地址（infra-media §7.3），挂在 `/app/infra/media/…`。
 *
 * - `policy`：网页服务启动时与站点配置的 `media.signedUrlValiditySeconds` 交叉校验；匿名、可缓存。
 * - `url/{fileId}`：长时间打开的页面里签名地址过期（图片 403）时，客户端换取当前桶的新地址。按 §7.1 判断
 *   可见性（匿名调用只拿得到匿名可见的媒体），按 IP 限流，`Cache-Control: private, max-age=60`。
 */
@Controller("/infra/media")
class MediaController(private val media: MediaLogic) {

    @Get("/policy")
    @AllowAnonymous
    suspend fun policy(http: HttpContext): MediaUrlPolicyVO {
        http.response.header("Cache-Control", "public, max-age=300")
        val p = media.urlPolicy()
        return MediaUrlPolicyVO(p.strategy, p.validitySeconds, p.bucketSeconds, p.imageParams)
    }

    @Get("/url/{fileId}")
    @AllowAnonymous
    @RateLimit(windowSeconds = 60, maxRequests = 120, scope = RateLimitScope.IP, message = InfraErrorCodes.MEDIA_URL_RATE_LIMITED)
    suspend fun url(http: HttpContext, identity: Identity?, @PathVariable fileId: String, @Query variant: String? = null): MediaUrlVO {
        val key = variant?.trim()?.takeIf { it.isNotEmpty() }
        val spec = key?.let { MediaVariant.byKey(it) }
        if (key != null && spec == null) {
            throw BadRequestException(
                InfraErrorCodes.MEDIA_VARIANT_UNKNOWN,
                listOf(ValidationError(path = "variant", message = InfraErrorCodes.MEDIA_VARIANT_UNKNOWN, code = InfraErrorCodes.MEDIA_VARIANT_UNKNOWN)),
            )
        }
        val (url, expiresAt) = media.currentUrl(fileId, spec, identity?.id?.toLongOrNull())
            ?: throw NotFoundException(InfraErrorCodes.FILE_NOT_FOUND)
        http.response.header("Cache-Control", "private, max-age=60")
        return MediaUrlVO(url, expiresAt)
    }
}
