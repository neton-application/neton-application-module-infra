package logic

import neton.core.http.BadRequestException
import neton.core.http.ValidationError
import port.InfraErrorCodes

/**
 * `businessType` → [FileBusinessPolicy] 登记表（spec MODULE_MEMBER_PROFILE_SPEC §4.2；infra-media §3）。
 *
 * infra 预置内置类型（[default]），并以 `bindIfAbsent` 装进上下文；其它模块在自己的
 * bootstrap 里取出来 [register] 自己的类型。只在装配期登记，服务期只读。
 *
 * 未注册的 businessType 通过 [policyOrThrow] / [policyOrNull] 返回失败 / null，
 * 调用方负责把错抛成 400 `INVALID_BUSINESS_TYPE`。
 */
class FileBusinessPolicyRegistry(
    initial: Collection<FileBusinessPolicy> = emptyList(),
) {
    private val policies = LinkedHashMap<String, FileBusinessPolicy>()

    init {
        initial.forEach { register(it) }
    }

    /**
     * 登记一个业务类型。code 重复直接报错——两个模块抢同一个类型名，谁覆盖谁都是事故。
     *
     * 注册期校验（错了在启动期炸，而不是第一次上传时）：
     * - code 合法（字母数字、`.`、`_`、`-`）
     * - 有物理资产目录（新类型必须声明 [FileBusinessPolicy.assetDir]）
     * - 大小上限、MIME 白名单、宽限期有效
     */
    fun register(policy: FileBusinessPolicy) {
        val code = policy.businessType
        require(CODE.matches(code)) { "invalid businessType code '$code'" }
        require(code !in policies) { "businessType '$code' is already registered" }
        require(policy.maxSizeBytes > 0) { "businessType '$code': maxSizeBytes must be > 0" }
        require(policy.allowedMimeTypes.isNotEmpty()) { "businessType '$code': allowedMimeTypes is empty" }
        require(policy.temporaryHours > 0 && policy.orphanedDays > 0) {
            "businessType '$code': temporaryHours / orphanedDays must be > 0"
        }
        policy.assetDir?.let {
            require(ASSET_DIR.matches(it)) { "businessType '$code': invalid assetDir '$it'" }
        }
        // 内置映射或显式目录必须有一个，否则 storageKeyOf 会在首次上传时才失败
        FileUploadLogic.assetDirOf(policy)
        policies[code] = policy
    }

    fun policyOrNull(businessType: String): FileBusinessPolicy? = policies[businessType]

    fun policyOrThrow(businessType: String): FileBusinessPolicy =
        policies[businessType]
            ?: throw BadRequestException(
                InfraErrorCodes.INVALID_BUSINESS_TYPE,
                listOf(ValidationError(path = "businessType", message = InfraErrorCodes.INVALID_BUSINESS_TYPE, code = InfraErrorCodes.INVALID_BUSINESS_TYPE)),
            )

    fun businessTypes(): Set<String> = policies.keys.toSet()

    fun all(): List<FileBusinessPolicy> = policies.values.toList()

    companion object {
        private val CODE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val ASSET_DIR = Regex("^[a-z0-9][a-z0-9-]{0,47}$")
        private const val MB = 1024L * 1024L

        private val IMAGE_AVATAR_MIMES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
        )

        /**
         * 内置策略（v1）。全部 `managedLifecycle = false`：现有调用方不 attach，
         * 这些类型的文件上传即为 attached，永远不进清理。
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
                    access = MediaAccess.PUBLIC,
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
                    access = MediaAccess.PUBLIC,
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
                    access = MediaAccess.OWNER,
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
                    access = MediaAccess.OWNER,
                    allowedMimeTypes = setOf(
                        "image/jpeg",
                        "image/png",
                        "application/pdf",
                    ),
                    maxSizeBytes = 20 * MB,
                ),
                FileBusinessPolicy(
                    businessType = "generic_image",
                    access = MediaAccess.PUBLIC,
                    allowedMimeTypes = IMAGE_AVATAR_MIMES,
                    maxSizeBytes = 5 * MB,
                ),
            )
            return FileBusinessPolicyRegistry(list)
        }
    }
}
