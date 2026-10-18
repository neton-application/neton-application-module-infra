package logic

import port.InfraErrorCodes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.random.Random
import kotlin.time.Clock
import model.FileInfo
import neton.core.http.BadRequestException
import neton.core.http.HttpException
import neton.core.http.ValidationError
import neton.core.http.NetonErrorCode
import neton.core.http.UploadFile
import neton.database.api.DbContext
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
    /** 托管生命周期类型的写入要在事务里与清理互斥（见 [storeManaged]）。 */
    private val db: DbContext,
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
    ): FileInfo = receive(Uploader.Account(ownerUid), businessType, file)

    /**
     * 后台上传（`POST /admin/infra/media/upload`）：与 [upload] 同一套策略校验、同一条落盘落库
     * 路径，托管类型同样以 temporary 起步。
     *
     * 后台账号与应用账号是两套 id 空间，所以上传者**不记进 `owner_uid`**（应用侧的「本人」判断
     * 全都比较这一列），而是记在 `admin_uploader_id`，`owner_uid` 留空。于是：
     * - 应用侧任何账号都不是这个文件的上传者：不能以本人身份读取 OWNER / DELEGATED 文件，
     *   也不能 attach 它的临时文件；
     * - attach 由后台保存路径以 `actorUserId = null`（系统 / 后台操作）完成，见 [MediaRules.decideAttach]。
     */
    suspend fun uploadAsAdmin(
        adminUserId: Long,
        businessType: String,
        file: UploadFile,
    ): FileInfo = receive(Uploader.Admin(adminUserId), businessType, file)

    private suspend fun receive(uploader: Uploader, businessType: String, file: UploadFile): FileInfo {
        val policy = policyRegistry.policyOrNull(businessType)
            ?: throw BadRequestException(InfraErrorCodes.INVALID_BUSINESS_TYPE, listOf(ValidationError(path = "businessType", message = InfraErrorCodes.INVALID_BUSINESS_TYPE, code = InfraErrorCodes.INVALID_BUSINESS_TYPE)))

        val mimeType = (file.contentType ?: "application/octet-stream").lowercase()
        if (mimeType !in policy.allowedMimeTypes) {
            throw HttpException(
                code = NetonErrorCode.INVALID_FORMAT,
                message = InfraErrorCodes.INVALID_MIME_TYPE,
                errors = listOf(ValidationError(path = "file", message = InfraErrorCodes.INVALID_MIME_TYPE, code = InfraErrorCodes.INVALID_MIME_TYPE)),
            )
        }
        if (file.size > policy.maxSizeBytes) {
            throw HttpException(
                code = NetonErrorCode.PAYLOAD_TOO_LARGE,
                message = InfraErrorCodes.FILE_TOO_LARGE,
                errors = listOf(ValidationError(path = "file", message = InfraErrorCodes.FILE_TOO_LARGE, code = InfraErrorCodes.FILE_TOO_LARGE)),
            )
        }
        if (file.size <= 0) {
            throw BadRequestException(InfraErrorCodes.EMPTY_FILE, listOf(ValidationError(path = "file", message = InfraErrorCodes.EMPTY_FILE, code = InfraErrorCodes.EMPTY_FILE)))
        }

        val bytes = file.bytes()
        if (bytes.size.toLong() != file.size) {
            log.warn(
                "FileUploadLogic.upload: declared size=${file.size} but bytes=${bytes.size} — using actual",
            )
        }

        return store(uploader, policy, mimeType, file.filename, bytes)
    }

    /**
     * 服务端已经拿到字节（例如服务端转存 `MediaService.importFromUrl`）时的入口：
     * 与 [upload] 同一套策略校验、同一条落盘落库路径。[mimeType] 由调用方嗅探确定。
     */
    suspend fun storeBytes(
        ownerUid: Long,
        businessType: String,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): FileInfo {
        val policy = policyRegistry.policyOrNull(businessType)
            ?: throw BadRequestException(InfraErrorCodes.INVALID_BUSINESS_TYPE, listOf(ValidationError(path = "businessType", message = InfraErrorCodes.INVALID_BUSINESS_TYPE, code = InfraErrorCodes.INVALID_BUSINESS_TYPE)))
        val mime = mimeType.lowercase()
        if (mime !in policy.allowedMimeTypes) {
            throw HttpException(
                code = NetonErrorCode.INVALID_FORMAT,
                message = InfraErrorCodes.INVALID_MIME_TYPE,
                errors = listOf(ValidationError(path = "file", message = InfraErrorCodes.INVALID_MIME_TYPE, code = InfraErrorCodes.INVALID_MIME_TYPE)),
            )
        }
        if (bytes.size.toLong() > policy.maxSizeBytes) {
            throw HttpException(
                code = NetonErrorCode.PAYLOAD_TOO_LARGE,
                message = InfraErrorCodes.FILE_TOO_LARGE,
                errors = listOf(ValidationError(path = "file", message = InfraErrorCodes.FILE_TOO_LARGE, code = InfraErrorCodes.FILE_TOO_LARGE)),
            )
        }
        if (bytes.isEmpty()) throw BadRequestException(InfraErrorCodes.EMPTY_FILE, listOf(ValidationError(path = "file", message = InfraErrorCodes.EMPTY_FILE, code = InfraErrorCodes.EMPTY_FILE)))
        return store(Uploader.Account(ownerUid), policy, mime, filename, bytes)
    }

    /**
     * 上传者。两套 id 空间分列存放：应用账号进 `owner_uid`，后台账号进 `admin_uploader_id`
     * （此时 `owner_uid` 为空），绝不混在同一列里比较。
     */
    sealed interface Uploader {
        data class Account(val uid: Long) : Uploader
        data class Admin(val adminUserId: Long) : Uploader
    }

    private val Uploader.ownerUid: Long? get() = (this as? Uploader.Account)?.uid
    private val Uploader.adminUploaderId: Long? get() = (this as? Uploader.Admin)?.adminUserId
    private val Uploader.logLabel: String
        get() = when (this) {
            is Uploader.Account -> "uid:$uid"
            is Uploader.Admin -> "admin:$adminUserId"
        }

    private suspend fun store(
        uploader: Uploader,
        policy: FileBusinessPolicy,
        mimeType: String,
        filename: String,
        bytes: ByteArray,
    ): FileInfo {
        val businessType = policy.businessType
        val sha256Hex = sha256Hex(bytes)
        val ext = extensionFor(mimeType, filename)
        val storageKey = storageKeyOf(policy, sha256Hex, ext)
        if (policy.managedLifecycle) {
            return storeManaged(uploader, policy, mimeType, filename, bytes, sha256Hex, storageKey)
        }
        // 幂等：同 owner + same businessType + same sha256 已上传过 → 直接返 existing。
        // 后台上传的行 owner_uid 为空，按 admin_uploader_id 去重；两类行互不命中。
        val existing = when (uploader) {
            is Uploader.Account -> FileInfoTable.oneWhere {
                and(
                    FileInfo::ownerUid eq uploader.uid,
                    FileInfo::businessType eq businessType,
                    FileInfo::sha256 eq sha256Hex,
                    FileInfo::status eq 1,
                )
            }
            is Uploader.Admin -> FileInfoTable.oneWhere {
                and(
                    FileInfo::adminUploaderId eq uploader.adminUserId,
                    FileInfo::businessType eq businessType,
                    FileInfo::sha256 eq sha256Hex,
                    FileInfo::status eq 1,
                )
            }
        }
        if (existing != null) {
            log.info(
                "FileUploadLogic.upload: dedup hit fileId=${existing.fileId} owner=${uploader.logLabel} sha=$sha256Hex",
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
            ownerUid = uploader.ownerUid,
            adminUploaderId = uploader.adminUploaderId,
            businessType = businessType,
            configId = null,
            name = filename,
            path = storageKey,
            url = url,
            mimeType = mimeType,
            size = bytes.size.toLong(),
            sha256 = sha256Hex,
            status = 1,
        )
        val inserted = FileInfoTable.insert(record)
        log.info(
            "FileUploadLogic.upload: ok fileId=$fileId owner=${uploader.logLabel} type=$businessType size=${bytes.size} sha=$sha256Hex",
        )
        // KSP 生成的 insert 通常回填自增 id；fileId 等保持原值
        return inserted
    }

    /**
     * 托管生命周期类型的写入（infra-media §6）：新文件以 `temporary` 起步，等业务 attach。
     *
     * 与清理任务互斥的两把锁：
     * 1. **路径咨询锁**：内容寻址让同类型同内容的多条记录共用一个物理对象；清理在删对象前
     *    拿同一把锁并确认没有存活记录还指着这条路径（[MediaLogic.purge]）。写对象 + 落库
     *    都在锁内完成，所以不会出现「刚写好的对象被清理删掉」。
     * 2. **行锁**：去重只复用仍是 `temporary` 的记录，并在行锁内刷新宽限期；清理恰好先锁住
     *    并删掉它时，`FOR UPDATE` 重新求值后取不到行，退回新建记录。
     *
     * 已 attached / orphaned 的同内容记录不复用——再上传一次就是一个新的临时文件，
     * 免得把用户第二次上传的图挂到第一次的引用关系上（不可复用的类型会因此 attach 失败）。
     */
    private suspend fun storeManaged(
        uploader: Uploader,
        policy: FileBusinessPolicy,
        mimeType: String,
        filename: String,
        bytes: ByteArray,
        sha256Hex: String,
        storageKey: String,
    ): FileInfo = db.transaction {
        lockStoragePath(this, storageKey)
        val now = Clock.System.now().toEpochMilliseconds()
        // 只复用同一上传者的临时记录；后台上传的行 owner_uid 为空，按 admin_uploader_id 匹配
        val uploaderClause = when (uploader) {
            is Uploader.Account -> "owner_uid = :owner"
            is Uploader.Admin -> "admin_uploader_id = :owner AND owner_uid IS NULL"
        }
        val ownerParam = when (uploader) {
            is Uploader.Account -> uploader.uid
            is Uploader.Admin -> uploader.adminUserId
        }
        val reusable = fetchAll(
            """
            SELECT id FROM infra_files
            WHERE $uploaderClause AND business_type = :type AND sha256 = :sha
              AND status = 1 AND lifecycle = '${port.MediaLifecycle.TEMPORARY.code}'
            ORDER BY id DESC LIMIT 1
            FOR UPDATE
            """.trimIndent(),
            mapOf("owner" to ownerParam, "type" to policy.businessType, "sha" to sha256Hex),
        ).firstOrNull()?.long("id")
        if (reusable != null) {
            execute(
                "UPDATE infra_files SET lifecycle_at = :now WHERE id = :id",
                mapOf("now" to now, "id" to reusable),
            )
            val existing = FileInfoTable.get(reusable)
            if (existing != null) {
                log.info(
                    "FileUploadLogic.upload: dedup hit (temporary) fileId=${existing.fileId} owner=${uploader.logLabel} sha=$sha256Hex",
                )
                return@transaction existing
            }
        }

        storage.write(storageKey, bytes, WriteOptions())
        val fileId = generateFileId()
        val inserted = FileInfoTable.insert(
            FileInfo(
                fileId = fileId,
                ownerUid = uploader.ownerUid,
                adminUploaderId = uploader.adminUploaderId,
                businessType = policy.businessType,
                configId = null,
                name = filename,
                path = storageKey,
                url = publicUrlFor(storageKey, fileId),
                mimeType = mimeType,
                size = bytes.size.toLong(),
                sha256 = sha256Hex,
                status = 1,
                lifecycle = port.MediaLifecycle.TEMPORARY.code,
                lifecycleAt = now,
            ),
        )
        log.info(
            "FileUploadLogic.upload: ok (temporary) fileId=$fileId owner=${uploader.logLabel} type=${policy.businessType} size=${bytes.size} sha=$sha256Hex",
        )
        inserted
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
        return relayUrlOf(fileId)
    }

    /**
     * 不看引用关系的读取判断。DELEGATED 在这里只认上传者——按引用放行要查引用表并回调
     * 拥有模块，见 [MediaLogic.canRead]（`GET /app/infra/file/get/{fileId}` 走那边）。
     */
    fun isReadableBy(record: FileInfo, viewerUid: Long?): Boolean {
        if (record.status != 1) return false
        val type = record.businessType ?: return false
        val policy = policyRegistry.policyOrNull(type) ?: return false
        return when (policy.access) {
            // PUBLIC(头像等)匿名可读:<img> 标签加载带不了鉴权头
            MediaAccess.PUBLIC -> true
            MediaAccess.OWNER, MediaAccess.DELEGATED -> viewerUid != null && record.ownerUid == viewerUid
        }
    }

    /** 服务端中转读取地址（带 [publicBaseUrl] 前缀，未配置时为相对路径）。 */
    fun relayUrlOf(fileId: String): String {
        val rel = publicUrlOf(fileId)
        val base = publicBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return rel
        return base + rel
    }

    /** 删除物理对象（清理任务用）。对象已不存在视为成功，删失败抛出由调用方重试。 */
    suspend fun deleteObject(path: String) {
        if (storage.exists(path)) storage.delete(path)
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

        /** 显式声明的目录优先，内置类型退回固定映射。 */
        fun assetDirOf(policy: FileBusinessPolicy): String =
            policy.assetDir ?: assetDirOf(policy.businessType)

        fun assetDirOf(businessType: String): String =
            ASSET_DIRS[businessType]
                ?: error(
                    "no asset directory mapped for businessType '$businessType'; " +
                        "declare FileBusinessPolicy.assetDir when registering it",
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
        fun storageKeyOf(businessType: String, sha256Hex: String, ext: String): String =
            storageKeyIn(assetDirOf(businessType), sha256Hex, ext)

        fun storageKeyOf(policy: FileBusinessPolicy, sha256Hex: String, ext: String): String =
            storageKeyIn(assetDirOf(policy), sha256Hex, ext)

        /**
         * 同一物理路径的写入与删除互斥（事务级咨询锁，事务结束自动释放）。
         * 托管类型上传与清理任务共用，见 [storeManaged] / [MediaLogic.purge]。
         */
        suspend fun lockStoragePath(db: DbContext, path: String) {
            db.fetchAll(
                "SELECT 1 AS ok FROM pg_advisory_xact_lock(hashtext(:path))",
                mapOf("path" to path),
            )
        }

        private fun storageKeyIn(dir: String, sha256Hex: String, ext: String): String {
            require(sha256Hex.length >= 4) {
                "sha256Hex too short for sharding: $sha256Hex"
            }
            val p1 = sha256Hex.substring(0, 2)
            val p2 = sha256Hex.substring(2, 4)
            return "$SYSTEM_PREFIX/$dir/$p1/$p2/$sha256Hex.$ext"
        }

        /** 服务端中转读取的相对地址；对象存储直读走存储源 baseUrl（见 publicUrlFor）。 */
        fun publicUrlOf(fileId: String): String = "/app/infra/file/get/$fileId"

        /**
         * 后台读取地址（`GET /admin/infra/media/get/{fileId}`）。相对路径：后台前端与后端同源，
         * 凭 HttpOnly 会话 Cookie 鉴权，所以 `<img src>` 直接可用，不需要签名。
         */
        fun adminUrlOf(fileId: String): String = "/admin/infra/media/get/$fileId"

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
                "image/gif" -> "gif"
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
