package logic

/**
 * `businessType` → [FileBusinessPolicy] 查表（spec MODULE_MEMBER_PROFILE_SPEC §4.2）。
 *
 * **第一版硬编码内置策略**。后续 v2 切外部配置文件 / DB 时换实现，
 * 上传链路（[FileUploadLogic] / `FileController`）调用方不改一行。
 *
 * 未注册的 businessType 通过 [policyOrThrow] / [policyOrNull] 返回失败 / null，
 * 调用方负责把错抛成 400 `INVALID_BUSINESS_TYPE`。
 */
class FileBusinessPolicyRegistry(
    private val policies: Map<String, FileBusinessPolicy>,
) {

    fun policyOrNull(businessType: String): FileBusinessPolicy? = policies[businessType]

    fun policyOrThrow(businessType: String): FileBusinessPolicy =
        policies[businessType]
            ?: throw IllegalArgumentException(
                "INVALID_BUSINESS_TYPE: '$businessType' not registered",
            )

    fun businessTypes(): Set<String> = policies.keys

    companion object {
        private const val MB = 1024L * 1024L

        private val IMAGE_AVATAR_MIMES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
        )

        /**
         * 默认硬编码策略（v1）。
         *
         * 边界与 spec MODULE_MEMBER_PROFILE_SPEC §6 字段校验汇总对齐：
         * - 头像类（member / group）：3 种 MIME，5 MB，public
         * - 反馈附件：图片 + PDF + text，20 MB，private
         * - KYC 证件：jpg/png/pdf，20 MB，private
         * - 通用图片：3 种 MIME，5 MB，public
         */
        fun default(): FileBusinessPolicyRegistry {
            val list = listOf(
                FileBusinessPolicy(
                    businessType = "member_avatar",
                    visibility = FileVisibility.PUBLIC,
                    allowedMimeTypes = IMAGE_AVATAR_MIMES,
                    maxSizeBytes = 5 * MB,
                    imagePolicy = ImagePolicy(
                        enabled = false, // PR-D 不执行；v2 image processing 上线后开启
                        crop = ImageCrop.SQUARE,
                        targetSize = 512,
                        outputFormat = "webp",
                        quality = 85,
                    ),
                ),
                FileBusinessPolicy(
                    businessType = "group_avatar",
                    visibility = FileVisibility.PUBLIC,
                    allowedMimeTypes = IMAGE_AVATAR_MIMES,
                    maxSizeBytes = 5 * MB,
                    imagePolicy = ImagePolicy(
                        enabled = false,
                        crop = ImageCrop.SQUARE,
                        targetSize = 512,
                        outputFormat = "webp",
                        quality = 85,
                    ),
                ),
                FileBusinessPolicy(
                    businessType = "feedback_attachment",
                    visibility = FileVisibility.PRIVATE,
                    allowedMimeTypes = setOf(
                        "image/jpeg",
                        "image/png",
                        "image/webp",
                        "application/pdf",
                        "text/plain",
                    ),
                    maxSizeBytes = 20 * MB,
                ),
                FileBusinessPolicy(
                    businessType = "kyc_document",
                    visibility = FileVisibility.PRIVATE,
                    allowedMimeTypes = setOf(
                        "image/jpeg",
                        "image/png",
                        "application/pdf",
                    ),
                    maxSizeBytes = 20 * MB,
                ),
                FileBusinessPolicy(
                    businessType = "generic_image",
                    visibility = FileVisibility.PUBLIC,
                    allowedMimeTypes = IMAGE_AVATAR_MIMES,
                    maxSizeBytes = 5 * MB,
                ),
            )
            return FileBusinessPolicyRegistry(list.associateBy { it.businessType })
        }
    }
}
