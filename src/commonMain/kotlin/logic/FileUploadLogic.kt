package logic

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.random.Random
import kotlin.time.Clock
import model.FileInfo
import neton.core.http.BadRequestException
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.core.http.UploadFile
import neton.database.dsl.*
import neton.logging.Logger
import neton.storage.StorageOperator
import neton.storage.WriteOptions
import table.FileInfoTable

/**
 * 应用唯一用户态文件入口（spec MODULE_MEMBER_PROFILE_SPEC §4.2）。
 *
 * 职责：
 * 1. 按 [FileBusinessPolicyRegistry] 校验 businessType / mime / size
 * 2. 计算 SHA-256 内容指纹
 * 3. 走 [StorageOperator]（neton-storage）写盘
 * 4. 落 `infra_files` 一行，返回 [FileInfo]（含 fileId / url）
 *
 * **目录布局是脚手架强制规范，不允许业务定制**（见 [storageKeyOf]）：
 *
 * ```
 * {SYSTEM_PREFIX}/{assetDir}/{sha256[0..2)}/{sha256[2..4)}/{sha256}.{ext}
 * ```
 *
 * 内容寻址，不带 ownerUid——归属记在 `infra_files` 元数据列，不进物理路径。
 *
 * 同样地 url 格式也固定 `/app/infra/file/get/{fileId}`，不暴露磁盘路径。
 */
class FileUploadLogic(
    private val log: Logger,
    private val storage: StorageOperator,
    private val policyRegistry: FileBusinessPolicyRegistry,
    /**
     * 走服务端中转读取时的绝对前缀（如 `https://h5.example.com/api`）。
     *
     * 仅用于 `/app/infra/file/get/{fileId}` 这条中转路径；对象存储直读地址由存储源的
     * `baseUrl` 决定（见 [publicUrlFor]）。
     */
    private val publicBaseUrl: String? = null,
) {

    /**
     * 接收用户态上传：校验 → 写盘 → 落库。
     *
     * 同 sha256 + ownerUid + businessType 的内容上传第二次时，**复用已有记录**
     * （storage_key 完全一致，写盘是幂等覆盖，DB 直接返 existing fileId）。
     */
    suspend fun upload(
        ownerUid: Long,
        businessType: String,
        file: UploadFile,
    ): FileInfo {
        val policy = policyRegistry.policyOrNull(businessType)
            ?: throw BadRequestException("INVALID_BUSINESS_TYPE: $businessType")

        val mimeType = (file.contentType ?: "application/octet-stream").lowercase()
        if (mimeType !in policy.allowedMimeTypes) {
            throw HttpException(
                code = NetonErrorCode.INVALID_FORMAT,
                message = "INVALID_MIME_TYPE: $mimeType not allowed for $businessType",
            )
        }
        if (file.size > policy.maxSizeBytes) {
            throw HttpException(
                code = NetonErrorCode.PAYLOAD_TOO_LARGE,
                message = "FILE_TOO_LARGE: ${file.size} > ${policy.maxSizeBytes}",
            )
        }
        if (file.size <= 0) {
            throw BadRequestException("EMPTY_FILE")
        }

        val bytes = file.bytes()
        if (bytes.size.toLong() != file.size) {
            log.warn(
                "FileUploadLogic.upload: declared size=${file.size} but bytes=${bytes.size} — using actual",
            )
        }

        val sha256Hex = sha256Hex(bytes)
        val ext = extensionFor(mimeType, file.filename)
        val storageKey = storageKeyOf(businessType, sha256Hex, ext)

        // 幂等：同 owner + same businessType + same sha256 已上传过 → 直接返 existing
        val existing = FileInfoTable.oneWhere {
            and(
                FileInfo::ownerUid eq ownerUid,
                FileInfo::businessType eq businessType,
                FileInfo::sha256 eq sha256Hex,
                FileInfo::status eq 1,
            )
        }
        if (existing != null) {
            log.info(
                "FileUploadLogic.upload: dedup hit fileId=${existing.fileId} owner=$ownerUid sha=$sha256Hex",
            )
            return existing
        }

        // 写盘（neton-storage 抽象，第一版 local，后续切 S3 仅改 storage.conf）
        storage.write(storageKey, bytes, WriteOptions())

        val fileId = generateFileId()
        val url = publicUrlFor(storageKey, fileId)
        val now = Clock.System.now().toEpochMilliseconds()

        val record = FileInfo(
            fileId = fileId,
            ownerUid = ownerUid,
            businessType = businessType,
            configId = null,
            name = file.filename,
            path = storageKey,
            url = url,
            mimeType = mimeType,
            size = bytes.size.toLong(),
            sha256 = sha256Hex,
            status = 1,
        )
        val inserted = FileInfoTable.insert(record)
        log.info(
            "FileUploadLogic.upload: ok fileId=$fileId owner=$ownerUid type=$businessType size=${bytes.size} sha=$sha256Hex",
        )
        // KSP 生成的 insert 通常回填自增 id；fileId 等保持原值
        return inserted
    }

    /** 按 fileId 查记录；下载/引用时用。 */
    suspend fun getByFileId(fileId: String): FileInfo? {
        if (fileId.isBlank()) return null
        return FileInfoTable.oneWhere { FileInfo::fileId eq fileId }
    }

    /**
     * 按 fileId 读字节。仅供 [FileController] 在确认完归属/可见性后调用。
     */
    suspend fun readBytes(record: FileInfo): ByteArray =
        storage.read(record.path)

    /**
     * 鉴权语义（spec §4.2）：
     * - PUBLIC：任意已登录用户可读
     * - PRIVATE：仅 owner 可读
     */
    /**
     * 下发给客户端的地址（spec MODULE_MEMBER_PROFILE_SPEC §4.2bis）。
     *
     * 存储源配了 `baseUrl`（对象存储/CDN）→ **直读地址** = baseUrl + 桶内路径。数据库里
     * 存的始终是那条相对路径，主机名只在这里拼上：换 CDN、换区域、Weey 与 PrivChat 用
     * 不同的桶，都不必回头改已有的每一行。
     *
     * 没配 `baseUrl`（本地磁盘等没有公开地址的源）→ 退回服务端中转 `/app/infra/file/get/{id}`。
     *
     * 🔴 绝不下发相对路径。客户端的头像缓存只认 `http(s)` 开头的地址，给它一个相对路径
     * 等于头像永远不下载——而且要等用户发现「设了头像不显示」才暴露（2026-09-09 生产实测）。
     */
    private fun publicUrlFor(storageKey: String, fileId: String): String {
        val objectBase = storage.baseUrl.trim().trimEnd('/')
        if (objectBase.isNotEmpty()) return "$objectBase/$storageKey"
        val rel = publicUrlOf(fileId)
        val base = publicBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return rel
        return base + rel
    }

    fun isReadableBy(record: FileInfo, viewerUid: Long?): Boolean {
        if (record.status != 1) return false
        val type = record.businessType ?: return false
        val policy = policyRegistry.policyOrNull(type) ?: return false
        return when (policy.visibility) {
            // PUBLIC(头像等)匿名可读:<img> 标签加载带不了鉴权头
            FileVisibility.PUBLIC -> true
            FileVisibility.PRIVATE -> viewerUid != null && record.ownerUid == viewerUid
        }
    }

    companion object {
        /**
         * 应用在共享对象存储里的**系统前缀**。
         *
         * privchat-application 与 privchat-server 共用一套凭证时各占一个顶级目录
         * （application = `platform/`，server = `chat/`），免得两套系统的内部分目录
         * 规则相撞。前缀写进每条记录的路径里，不是读的时候再拼——以后改前缀只影响新
         * 文件，已有对象仍按自己那条路径读得到。
         */
        const val SYSTEM_PREFIX: String = "platform"

        /**
         * businessType → 物理资产目录。
         *
         * 两者**不是同一个东西**：`businessType` 是业务分类，鉴权和配额按它走，产品
         * 想改名是业务侧的自由；而路径一旦写进数据库就不能动。用显式映射把它们连起来，
         * 加业务类型时必须顺带决定目录名——漏了就在启动期炸，而不是安静地拿业务名当
         * 目录写进去。
         */
        private val ASSET_DIRS: Map<String, String> = mapOf(
            "member_avatar" to "avatars",
            "group_avatar" to "group-avatars",
            "feedback_attachment" to "feedback",
            "kyc_document" to "kyc",
            "generic_image" to "images",
        )

        fun assetDirOf(businessType: String): String =
            ASSET_DIRS[businessType]
                ?: error(
                    "no asset directory mapped for businessType '$businessType'; " +
                        "add it to FileUploadLogic.ASSET_DIRS",
                )

        /**
         * 物理 storage key（spec MODULE_MEMBER_PROFILE_SPEC §4.2bis）。
         *
         * ```
         * {SYSTEM_PREFIX}/{assetDir}/{sha256[0..2)}/{sha256[2..4)}/{sha256}.{ext}
         * ```
         *
         * 例：`platform/avatars/32/9e/329e612ae747790422b0a2cb9f18b3c9.jpg`
         *
         * **内容寻址，不带 ownerUid**：
         * 1. 多个 owner 上传同一内容 → 共享同一物理对象（去重）；
         * 2. sha256 两层前缀分片，大量上传时目录不倾斜；
         * 3. 路径不暴露 uid。
         *
         * 归属 / 业务关系记录在 `infra_files` 元数据列，不进物理路径。
         */
        fun storageKeyOf(businessType: String, sha256Hex: String, ext: String): String {
            require(sha256Hex.length >= 4) {
                "sha256Hex too short for sharding: $sha256Hex"
            }
            val dir = assetDirOf(businessType)
            val p1 = sha256Hex.substring(0, 2)
            val p2 = sha256Hex.substring(2, 4)
            return "$SYSTEM_PREFIX/$dir/$p1/$p2/$sha256Hex.$ext"
        }

        /** 服务端中转读取的相对地址；对象存储直读走存储源 baseUrl（见 publicUrlFor）。 */
        fun publicUrlOf(fileId: String): String = "/app/infra/file/get/$fileId"

        private val sha256 = CryptographyProvider.Default.get(SHA256).hasher()

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = sha256.hashBlocking(bytes)
            return digest.toHexString()
        }

        @OptIn(ExperimentalStdlibApi::class)
        private fun ByteArray.toHexString(): String =
            joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        private fun extensionFor(mimeType: String, fallbackName: String): String =
            when (mimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "application/pdf" -> "pdf"
                "text/plain" -> "txt"
                else -> fallbackName.substringAfterLast('.', missingDelimiterValue = "bin")
                    .lowercase()
                    .takeIf { it.isNotBlank() && it.length <= 8 }
                    ?: "bin"
            }

        private fun generateFileId(): String {
            // 16 byte random → 32 hex chars。`file_` 前缀方便 grep / log。
            val bytes = ByteArray(16).also { Random.nextBytes(it) }
            val hex = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            return "file_$hex"
        }
    }
}
