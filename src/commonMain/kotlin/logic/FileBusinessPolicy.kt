package logic

/**
 * 用户态上传文件的业务策略（spec MODULE_MEMBER_PROFILE_SPEC §4.2）。
 *
 * 每个 `businessType` 对应一组上传约束。第一版策略由 [FileBusinessPolicyRegistry]
 * 硬编码 + 进程加载；后续切配置文件 / DB 时只换 registry 实现，
 * 上传链路（[FileUploadLogic] / `FileController`）零改动。
 *
 * 目录布局**不是策略的一部分**——脚手架强制 `{businessType}/{ownerUid}/{sha256}.{ext}`，
 * controller / logic 不得自行拼路径。
 */
data class FileBusinessPolicy(
    val businessType: String,
    val visibility: FileVisibility,
    val allowedMimeTypes: Set<String>,
    val maxSizeBytes: Long,
    /**
     * 图片处理策略（PR-D **不执行**，预留字段）。
     *
     * 后续 v2 image processing 上线后，由 worker 读取本字段决定是否
     * 生成裁剪/压缩/缩略图副本。本字段不影响 PR-D 的上传路径。
     */
    val imagePolicy: ImagePolicy? = null,
)

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
