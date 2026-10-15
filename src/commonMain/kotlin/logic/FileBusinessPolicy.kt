package logic

/**
 * 用户态上传文件的业务策略（spec MODULE_MEMBER_PROFILE_SPEC §4.2；infra-media §3）。
 *
 * 每个 `businessType` 对应一组上传约束。内置类型由 [FileBusinessPolicyRegistry.default]
 * 预置；其它模块在自己的 bootstrap 里 `ctx.get(FileBusinessPolicyRegistry::class).register(...)`
 * 登记自己的类型（如 `content.image`），上传链路（[FileUploadLogic] / `FileController`）零改动。
 *
 * 目录布局**不是策略的一部分**——脚手架强制 `{businessType}/{sha256[0..2)}/{sha256[2..4)}/{sha256}.{ext}`
 * （内容寻址，不带 ownerUid；见 [FileUploadLogic.storageKeyOf]），controller / logic 不得自行拼路径。
 * 新类型只声明目录名 [assetDir]，路径规则仍由脚手架决定。
 */
data class FileBusinessPolicy(
    val businessType: String,
    /**
     * 访问方式（infra-media §3 / §7.1）。
     * - [MediaAccess.PUBLIC]：任何人可读（等同旧 `FileVisibility.PUBLIC`）
     * - [MediaAccess.OWNER]：仅上传者可读（等同旧 `FileVisibility.PRIVATE`）
     * - [MediaAccess.DELEGATED]：按引用判断，回调引用对象所属模块（`port.MediaOwnerAccess`）
     */
    val access: MediaAccess,
    val allowedMimeTypes: Set<String>,
    val maxSizeBytes: Long,
    /**
     * 图片处理策略（PR-D **不执行**，预留字段）。
     *
     * 后续 v2 image processing 上线后，由 worker 读取本字段决定是否
     * 生成裁剪/压缩/缩略图副本。本字段不影响 PR-D 的上传路径。
     */
    val imagePolicy: ImagePolicy? = null,
    /** 可以 attach 到哪些用途；空表示默认只能用于与类型同名的用途。 */
    val usages: Set<String> = emptySet(),
    /** 已关联的文件能否被其他对象再次引用。 */
    val reusable: Boolean = false,
    /**
     * 是否进入「临时 → 关联 → 孤儿 → 清理」生命周期（infra-media §6）。
     *
     * 🔴 内置类型一律 false：现有调用方从不 attach，开了就会被清理任务删掉。
     * 只有显式声明的新类型才以 temporary 起步。
     */
    val managedLifecycle: Boolean = false,
    /** temporary 文件的宽限期（小时），超过仍未关联就清理。仅 [managedLifecycle] 时生效。 */
    val temporaryHours: Int = 24,
    /** orphaned 文件的宽限期（天），超过仍未重新关联就清理。仅 [managedLifecycle] 时生效。 */
    val orphanedDays: Int = 7,
    /**
     * 物理资产目录名（`platform/{assetDir}/...`）。内置类型走 [FileUploadLogic] 的固定映射，
     * 可以不填；新登记的类型必须填，注册时校验。
     */
    val assetDir: String? = null,
) {
    /** 实际允许的用途集合：未声明时默认只允许与类型同名的用途。 */
    val effectiveUsages: Set<String>
        get() = usages.ifEmpty { setOf(businessType) }

    /** 旧可见性视图，保留给按 PUBLIC / PRIVATE 判断的既有代码。DELEGATED 视为非公开。 */
    val visibility: FileVisibility
        get() = if (access == MediaAccess.PUBLIC) FileVisibility.PUBLIC else FileVisibility.PRIVATE
}

/** 媒体访问方式（infra-media §3）。 */
enum class MediaAccess {
    /** 任何人可读（头像、公开文章配图），匿名也可。 */
    PUBLIC,
    /** 仅上传者可读（反馈附件、证件）。 */
    OWNER,
    /** 按引用判断：任一引用对象对访问者可见即可读；没有引用的 temporary 文件只有上传者可读。 */
    DELEGATED,
}

enum class FileVisibility {
    /** 任意已登录用户可读。member_avatar / group_avatar / generic_image 用。 */
    PUBLIC,
    /** 仅 owner 可读。feedback_attachment / kyc_document 用。 */
    PRIVATE,
}

/**
 * 图片裁剪 / 压缩 / 缩略图策略（v2 image processing，PR-D **不执行**）。
 *
 * 写在数据结构里只是为了让 registry 字段稳定，后续不需要重新设计 schema。
 */
data class ImagePolicy(
    val enabled: Boolean = false,
    val crop: ImageCrop? = null,
    val targetSize: Int? = null,
    val outputFormat: String? = null,
    val quality: Int? = null,
)

enum class ImageCrop {
    SQUARE,
    KEEP,
}
