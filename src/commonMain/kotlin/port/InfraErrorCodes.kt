package port

/**
 * module-infra 的业务错误机器码目录（规范 `neton-docs/docs/zh-hans/spec/application/infra-errors.md`）。
 *
 * HTTP 错误的 `message` 只能是这里的常量；细节放进 `ValidationError(path, CODE, CODE)`。
 * [MediaErrorCode] 的每个名字也在这里（取值 = 枚举名），调用方把 `AttachResult.Rejected` /
 * `ImportResult.Rejected` 的 `code.name` 抛给客户端时，前端用同一套 `error.<CODE>` 词条翻译。
 */
object InfraErrorCodes {
    // ---- 文件上传 / 读取 ----
    /** businessType 未注册。 */
    const val INVALID_BUSINESS_TYPE = "INVALID_BUSINESS_TYPE"
    /** MIME 不在该业务类型的白名单里。 */
    const val INVALID_MIME_TYPE = "INVALID_MIME_TYPE"
    /** 文件超过该业务类型的大小上限。 */
    const val FILE_TOO_LARGE = "FILE_TOO_LARGE"
    /** 文件为空。 */
    const val EMPTY_FILE = "EMPTY_FILE"
    /** 上传请求不是 multipart。 */
    const val INVALID_CONTENT_TYPE = "INVALID_CONTENT_TYPE"
    /** 缺少表单字段 `file`。 */
    const val MISSING_FILE_FIELD = "MISSING_FILE_FIELD"
    /** 缺少表单字段 `businessType`。 */
    const val MISSING_BUSINESS_TYPE = "MISSING_BUSINESS_TYPE"
    /** 上传过于频繁。 */
    const val UPLOAD_RATE_LIMITED = "UPLOAD_RATE_LIMITED"
    /** 后台身份不是数字 id。 */
    const val INVALID_ADMIN_IDENTITY = "INVALID_ADMIN_IDENTITY"
    /** 文件不存在。 */
    const val FILE_NOT_FOUND = "FILE_NOT_FOUND"
    /** 文件已删除或正在清理（后台读取）。 */
    const val FILE_DELETED = "FILE_DELETED"
    /** 无权读取该文件。 */
    const val FILE_FORBIDDEN = "FILE_FORBIDDEN"
    /** 换取媒体地址过于频繁（`GET /app/infra/media/url/{fileId}`，按 IP）。 */
    const val MEDIA_URL_RATE_LIMITED = "MEDIA_URL_RATE_LIMITED"
    /** 不认识的派生规格（`variant` 不是 w180 / w360 / w720 / w1080 / w1440 / og）。 */
    const val MEDIA_VARIANT_UNKNOWN = "MEDIA_VARIANT_UNKNOWN"

    // ---- 后台运维 ----
    /** 文件存储配置不存在。 */
    const val FILE_CONFIG_NOT_FOUND = "FILE_CONFIG_NOT_FOUND"
    /** 定时任务不存在。 */
    const val JOB_NOT_FOUND = "JOB_NOT_FOUND"
    /** API 错误日志不存在。 */
    const val API_ERROR_LOG_NOT_FOUND = "API_ERROR_LOG_NOT_FOUND"
    /** 领域事件不存在。 */
    const val DOMAIN_EVENT_NOT_FOUND = "DOMAIN_EVENT_NOT_FOUND"
    /** 只有终态失败（3）的事件可以重投。 */
    const val DOMAIN_EVENT_NOT_REQUEUEABLE = "DOMAIN_EVENT_NOT_REQUEUEABLE"
    /** 只有待投递（0）或失败（3）的事件可以丢弃。 */
    const val DOMAIN_EVENT_NOT_DISCARDABLE = "DOMAIN_EVENT_NOT_DISCARDABLE"

    // ---- MediaService 结果码（= MediaErrorCode 枚举名，infra-media §4.3 / §6.2） ----
    /** 文件的业务类型已不在注册表里。 */
    const val UNKNOWN_BUSINESS_TYPE = "UNKNOWN_BUSINESS_TYPE"
    /** 文件的业务类型不允许用于该用途。 */
    const val USAGE_NOT_ALLOWED = "USAGE_NOT_ALLOWED"
    /** 临时 / OWNER 文件只能由上传者关联。 */
    const val NOT_FILE_OWNER = "NOT_FILE_OWNER"
    /** 已被引用的文件，其业务类型不允许复用。 */
    const val NOT_REUSABLE = "NOT_REUSABLE"
    /** 复用 DELEGATED 文件时操作者看不到任何引用对象。 */
    const val ACCESS_DENIED = "ACCESS_DENIED"
    /** attach 参数为空或超长。 */
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    /** 转存地址无效。 */
    const val INVALID_URL = "INVALID_URL"
    /** 不可信地址不是 http/https。 */
    const val URL_SCHEME_FORBIDDEN = "URL_SCHEME_FORBIDDEN"
    /** 不可信地址的主机是 / 解析到非公网地址（本机、内网、链路本地、保留段…），或是非规范的 IP 字面量。 */
    const val URL_TARGET_FORBIDDEN = "URL_TARGET_FORBIDDEN"
    /** 不可信地址的端口不在放行列表（默认 80 / 443）。 */
    const val URL_PORT_FORBIDDEN = "URL_PORT_FORBIDDEN"
    /** 不可信地址的主机名解析失败。 */
    const val URL_RESOLVE_FAILED = "URL_RESOLVE_FAILED"
    /** 转存跟随重定向超过上限（5 跳）。 */
    const val TOO_MANY_REDIRECTS = "TOO_MANY_REDIRECTS"
    /** 拉取远端文件失败。 */
    const val FETCH_FAILED = "FETCH_FAILED"
    /** 本部署没有出站 HTTP 客户端。 */
    const val NOT_AVAILABLE = "NOT_AVAILABLE"
}
