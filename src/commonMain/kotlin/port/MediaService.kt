package port

/**
 * 媒体关联与访问契约（infra-media §6.2 / §7，M1）。
 *
 * 业务模块只保存 fileId（MediaRef），通过本接口：
 * - 上传后确认关联（[attach]），对象删除时解除（[detach] / [detachAll]）；
 * - 展示时批量展开成访问地址（[resolve]），非公开文件只发短期签名地址；
 * - 把外部文件转存成平台媒体（[importFromUrl]）。
 *
 * 由 infra 在 bootstrap 里 `bindIfAbsent`，其它模块 `ctx.get(MediaService::class)` 使用。
 * owner 用 (ownerType, ownerId) 两个字符串表达，例如 `("content.item", "1024")`。
 */
interface MediaService {
    /**
     * 确认关联。任何一项校验不通过都返回 [AttachResult.Rejected]，不静默放行。
     *
     * @param actorUserId 操作者；null 表示系统 / 后台操作（不做归属与读取权限校验）。
     */
    suspend fun attach(
        fileId: String,
        ownerType: String,
        ownerId: String,
        usage: String,
        actorUserId: Long?,
    ): AttachResult

    /** 解除一个引用；托管文件引用数归零时进入 orphaned。返回是否真的删掉了一条引用。 */
    suspend fun detach(fileId: String, ownerType: String, ownerId: String, usage: String): Boolean

    /** 对象删除时解除它的全部引用。返回解除的引用条数。 */
    suspend fun detachAll(ownerType: String, ownerId: String): Int

    /**
     * 批量展开。结果**只包含访问者可读的文件**：不存在、已删除或无权读取的 fileId 不出现在结果里，
     * 也就不会为它签发地址。
     *
     * @param viewerUserId 访问者；null 表示匿名。
     */
    suspend fun resolve(fileIds: Collection<String>, viewerUserId: Long?): Map<String, MediaView>

    /**
     * 服务端转存：下载 [url]（仅 http / https，带超时、大小上限、MIME 嗅探），按 [businessType]
     * 的策略走正常落盘路径，文件归属 [ownerUserId]。托管类型的结果同样是 temporary，需要再 attach。
     */
    suspend fun importFromUrl(businessType: String, url: String, ownerUserId: Long): ImportResult
}

/**
 * 委托访问回调（infra-media §7.1 `DELEGATED`）：引用对象所属模块判断访问者能否查看该对象。
 *
 * 由业务模块（或组合多个模块的套件）绑定；infra **在请求时** `ctx.getOrNull` 读取，没有绑定时
 * DELEGATED 文件只有上传者可读。
 */
fun interface MediaOwnerAccess {
    suspend fun canView(viewerUserId: Long?, ownerType: String, ownerId: String): Boolean
}

/** 媒体生命周期（infra-media §6.1），[code] 即 `infra_files.lifecycle` 的取值。 */
enum class MediaLifecycle(val code: String) {
    TEMPORARY("temporary"),
    ATTACHED("attached"),
    ORPHANED("orphaned"),
    DELETED("deleted"),
    ;

    companion object {
        /** 未知取值按 attached 处理：历史行与非托管类型都是 attached。 */
        fun fromCode(code: String?): MediaLifecycle =
            entries.firstOrNull { it.code == code } ?: ATTACHED
    }
}

/** 媒体操作的失败原因。名字即机器码，可直接作为错误消息抛给前端做 i18n。 */
enum class MediaErrorCode {
    // ---- attach ----
    /** 文件不存在。 */
    FILE_NOT_FOUND,
    /** 文件已删除或正在清理。 */
    FILE_DELETED,
    /** 文件的业务类型已不在注册表里。 */
    UNKNOWN_BUSINESS_TYPE,
    /** 文件的业务类型不允许用于该用途。 */
    USAGE_NOT_ALLOWED,
    /** 临时文件只能由上传者关联；或复用 OWNER 文件时操作者不是上传者。 */
    NOT_FILE_OWNER,
    /** 已被其他对象引用的文件，其业务类型不允许复用。 */
    NOT_REUSABLE,
    /** 复用 DELEGATED 文件时操作者看不到任何引用对象。 */
    ACCESS_DENIED,
    /** 参数为空或超长。 */
    INVALID_ARGUMENT,

    // ---- importFromUrl ----
    INVALID_BUSINESS_TYPE,
    INVALID_URL,
    UNSUPPORTED_SCHEME,
    FORBIDDEN_HOST,
    FETCH_FAILED,
    FILE_TOO_LARGE,
    INVALID_MIME_TYPE,
    EMPTY_FILE,
    /** 本部署没有出站 HTTP 客户端。 */
    NOT_AVAILABLE,
}

sealed interface AttachResult {
    /** 关联成功；[alreadyAttached] 表示同一引用早已存在（幂等）。 */
    data class Attached(val fileId: String, val alreadyAttached: Boolean) : AttachResult

    data class Rejected(val code: MediaErrorCode, val message: String) : AttachResult
}

sealed interface ImportResult {
    data class Imported(val file: MediaView) : ImportResult

    data class Rejected(val code: MediaErrorCode, val message: String) : ImportResult
}

/**
 * 展开后的媒体（content-platform-architecture §6.1 的 M1 子集）。
 *
 * @property url 访问地址。PUBLIC 为长期地址；OWNER / DELEGATED 为短期签名地址，[expiresAt] 为其过期时刻。
 * @property expiresAt 签名过期时刻（epoch 秒）；长期地址为 null。
 */
data class MediaView(
    val fileId: String,
    val url: String,
    val mimeType: String,
    val size: Long,
    val businessType: String,
    val lifecycle: MediaLifecycle,
    val expiresAt: Long? = null,
)
