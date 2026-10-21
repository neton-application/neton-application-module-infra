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
     * @param actorUserId 应用账号操作者；null 表示系统 / 后台操作（不做归属与读取权限校验）。
     *   后台保存路径一律传 null：后台账号与应用账号是两套 id 空间，管理员 id 不能当成应用账号比较。
     *   后台上传的文件（`POST /admin/infra/media/upload`）没有应用侧上传者，它的临时文件只能以 null 关联。
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
     * 后台展开（编辑器、审核、评论管理预览草稿 / 会员可见 / 临时文件）。
     *
     * 不做按访问者的判断：**任何未删除的文件**都返回（含 temporary）。调用方必须是已通过后台
     * 权限校验的接口；这里只发地址，真正读字节的 `GET /admin/infra/media/get/{fileId}` 还要
     * `infra:media:query` 权限。
     *
     * 地址：PUBLIC 与 [resolve] 相同（长期地址）；OWNER / DELEGATED 为后台读取地址
     * `/admin/infra/media/get/{fileId}`（相对路径，凭后台会话 Cookie 读取，`expiresAt` 为 null）。
     * 不签发应用侧签名地址，免得后台预览产生能被转发出去的匿名可读链接。
     *
     * 默认实现返回空表，仅为不支持后台展开的替身实现（测试 fake）保留源码兼容。
     */
    suspend fun resolveForAdmin(fileIds: Collection<String>): Map<String, MediaView> = emptyMap()

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
    URL_SCHEME_FORBIDDEN,
    URL_TARGET_FORBIDDEN,
    URL_PORT_FORBIDDEN,
    URL_RESOLVE_FAILED,
    /** 跟随重定向超过上限（5 跳）。 */
    TOO_MANY_REDIRECTS,
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
 * 展开后的媒体（content-platform-architecture §6.1 的 M1 子集 + infra-media §7.3）。
 *
 * @property url 访问地址。按 CDN 地址策略（§7.3）：对匿名访问者可见的媒体在开启 CDN 时是 CDN 地址
 *   （`public` 永久、签名策略按时间桶确定性签名）；其余照旧——PUBLIC 长期地址，OWNER / DELEGATED 短期签名地址。
 * @property expiresAt 地址过期时刻（epoch 秒）；长期地址为 null。
 * @property width 图片原始宽度（像素，已按 EXIF 方向校正）；非图片或未识别为 null。
 * @property height 图片原始高度。
 * @property variants 派生规格地址，键为 `logic.MediaVariant.key`（`w180`、`w360`、`w720`、`w1080`、`w1440`、`og`）。
 *   只有 CDN 地址且配置了图片处理方言时才有；比原图宽的 `w*` 档位不输出。
 */
data class MediaView(
    val fileId: String,
    val url: String,
    val mimeType: String,
    val size: Long,
    val businessType: String,
    val lifecycle: MediaLifecycle,
    val expiresAt: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val variants: Map<String, String> = emptyMap(),
)
