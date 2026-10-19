package logic

import kotlinx.coroutines.flow.collect
import model.FileInfo
import neton.core.http.HttpException
import neton.database.api.DbContext
import neton.database.api.Row
import neton.database.dsl.*
import neton.http.client.HttpClient
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientStreamChunk
import neton.http.client.HttpClientTimeouts
import neton.logging.Logger
import port.AttachResult
import port.ImportResult
import port.MediaErrorCode
import port.MediaLifecycle
import port.MediaOwnerAccess
import port.MediaService
import port.MediaView
import table.FileInfoTable
import kotlin.time.Clock

/**
 * [MediaService] 的 infra 实现：引用登记、生命周期、按访问者展开地址、服务端转存、清理。
 *
 * 并发模型（infra-media §6.2）：attach / detach / 清理都在事务里先对 `infra_files` 行
 * `SELECT … FOR UPDATE`，再在锁内重新读取状态做决定。因此
 * - 清理先拿到锁 → 标 deleted 提交 → attach 随后读到 deleted 被拒绝；
 * - attach 先拿到锁 → 引用写入提交 → 清理随后在锁内看到引用数 > 0 / 状态 attached 放弃。
 *
 * 物理对象的删除见 [purge]：内容寻址让同路径多条记录共用一个对象，删之前在路径咨询锁内
 * 确认没有存活记录还指着它（与 [FileUploadLogic] 托管上传共用同一把锁）。
 */
class MediaLogic(
    private val log: Logger,
    private val db: DbContext,
    private val policyRegistry: FileBusinessPolicyRegistry,
    private val files: FileUploadLogic,
    private val signer: MediaUrlSigner,
    /** 请求时读取：由套件模块在 infra 之后绑定，不能在 bootstrap 捕获。 */
    private val ownerAccess: () -> MediaOwnerAccess?,
    /** 转存用的出站客户端：必须是不可信 URL 客户端（[port.UntrustedHttpClient]），地址来自用户 / 外部内容。 */
    private val httpClient: () -> HttpClient?,
    private val importConfig: ImportConfig = ImportConfig(),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : MediaService {

    data class ImportConfig(
        val connectMillis: Long = 5_000,
        val requestMillis: Long = 30_000,
        val socketMillis: Long = 15_000,
    )

    data class CleanupReport(val markedDeleted: Int, val purged: Int, val purgeFailed: Int)

    // ================================================================
    // attach / detach
    // ================================================================

    override suspend fun attach(
        fileId: String,
        ownerType: String,
        ownerId: String,
        usage: String,
        actorUserId: Long?,
    ): AttachResult {
        invalidArgument(fileId, ownerType, ownerId, usage)?.let { return it }
        return db.transaction {
            val row = lockFile(this, fileId)
            val snapshot = row?.let { snapshotOf(it) }
            val refs = if (row == null) emptyList() else referencesOf(this, fileId)
            when (val d = MediaRules.decideAttach(snapshot, refs, ownerType, ownerId, usage, actorUserId)) {
                is MediaRules.AttachDecision.Reject -> AttachResult.Rejected(d.code, d.message)
                MediaRules.AttachDecision.AlreadyAttached -> AttachResult.Attached(fileId, alreadyAttached = true)
                MediaRules.AttachDecision.Attach ->
                    insertReference(this, fileId, ownerType, ownerId, usage, snapshot!!.lifecycle)
                MediaRules.AttachDecision.AttachIfActorCanViewExistingOwner -> {
                    if (canViewAnyOwner(actorUserId, refs)) {
                        insertReference(this, fileId, ownerType, ownerId, usage, snapshot!!.lifecycle)
                    } else {
                        AttachResult.Rejected(MediaErrorCode.ACCESS_DENIED, "actor cannot view any object referencing this file")
                    }
                }
            }
        }
    }

    private suspend fun insertReference(
        tx: DbContext,
        fileId: String,
        ownerType: String,
        ownerId: String,
        usage: String,
        current: MediaLifecycle,
    ): AttachResult {
        val ts = now()
        tx.execute(
            """
            INSERT INTO infra_media_references (file_id, owner_type, owner_id, usage, created_at)
            VALUES (:fileId, :ownerType, :ownerId, :usage, :ts)
            ON CONFLICT (file_id, owner_type, owner_id, usage) DO NOTHING
            """.trimIndent(),
            mapOf("fileId" to fileId, "ownerType" to ownerType, "ownerId" to ownerId, "usage" to usage, "ts" to ts),
        )
        val next = MediaRules.lifecycleAfterAttach(current)
        if (next != current) setLifecycle(tx, fileId, next, ts)
        log.info(
            "media.attach",
            mapOf("fileId" to fileId, "ownerType" to ownerType, "ownerId" to ownerId, "usage" to usage, "from" to current.code),
        )
        return AttachResult.Attached(fileId, alreadyAttached = false)
    }

    override suspend fun detach(fileId: String, ownerType: String, ownerId: String, usage: String): Boolean =
        db.transaction {
            val row = lockFile(this, fileId)
            val removed = execute(
                """
                DELETE FROM infra_media_references
                WHERE file_id = :fileId AND owner_type = :ownerType AND owner_id = :ownerId AND usage = :usage
                """.trimIndent(),
                mapOf("fileId" to fileId, "ownerType" to ownerType, "ownerId" to ownerId, "usage" to usage),
            )
            if (row != null && removed > 0) orphanIfUnreferenced(this, fileId, row)
            removed > 0
        }

    override suspend fun detachAll(ownerType: String, ownerId: String): Int = db.transaction {
        // 固定加锁顺序（按 file_id），与并发的 detachAll / attach 不互相死锁
        val fileIds = fetchAll(
            "SELECT DISTINCT file_id FROM infra_media_references WHERE owner_type = :t AND owner_id = :o ORDER BY file_id",
            mapOf("t" to ownerType, "o" to ownerId),
        ).map { it.string("file_id") }
        var total = 0L
        for (fileId in fileIds) {
            val row = lockFile(this, fileId)
            val removed = execute(
                "DELETE FROM infra_media_references WHERE file_id = :f AND owner_type = :t AND owner_id = :o",
                mapOf("f" to fileId, "t" to ownerType, "o" to ownerId),
            )
            total += removed
            if (row != null && removed > 0) orphanIfUnreferenced(this, fileId, row)
        }
        if (total > 0) log.info("media.detach-all", mapOf("ownerType" to ownerType, "ownerId" to ownerId, "removed" to total))
        total.toInt()
    }

    private suspend fun orphanIfUnreferenced(tx: DbContext, fileId: String, lockedRow: Row) {
        val snapshot = snapshotOf(lockedRow)
        val remaining = countReferences(tx, fileId)
        val next = MediaRules.lifecycleAfterDetach(snapshot.policy, snapshot.lifecycle, remaining)
        if (next != snapshot.lifecycle) setLifecycle(tx, fileId, next, now())
    }

    // ================================================================
    // 读取判断 / resolve
    // ================================================================

    /** `GET /app/infra/file/get/{fileId}` 的身份读取判断（不含签名）。 */
    suspend fun canRead(record: FileInfo, viewerUserId: Long?): Boolean {
        val policy = record.businessType?.let { policyRegistry.policyOrNull(it) }
        val lifecycle = MediaLifecycle.fromCode(record.lifecycle)
        MediaRules.readWithoutReferences(policy, record.status, lifecycle, record.ownerUid, viewerUserId)
            ?.let { return it }
        val fileId = record.fileId ?: return false
        return canViewAnyOwner(viewerUserId, referencesOf(db, fileId))
    }

    fun verifySignature(fileId: String, exp: Long?, sig: String?): Boolean = signer.verify(fileId, exp, sig)

    fun signatureRemainingSeconds(exp: Long): Long = signer.remainingSeconds(exp)

    /** 文件是否需要按非公开方式下发（OWNER / DELEGATED）。 */
    fun isPublic(record: FileInfo): Boolean =
        record.businessType?.let { policyRegistry.policyOrNull(it) }?.access == MediaAccess.PUBLIC

    fun isDelegated(record: FileInfo): Boolean =
        record.businessType?.let { policyRegistry.policyOrNull(it) }?.access == MediaAccess.DELEGATED

    override suspend fun resolve(fileIds: Collection<String>, viewerUserId: Long?): Map<String, MediaView> {
        val ids = fileIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val records = ids.chunked(IN_CHUNK).flatMap { chunk ->
            FileInfoTable.query { where { FileInfo::fileId `in` chunk } }.list()
        }
        val pendingDelegated = mutableListOf<FileInfo>()
        val readable = mutableListOf<FileInfo>()
        for (r in records) {
            val policy = r.businessType?.let { policyRegistry.policyOrNull(it) }
            when (MediaRules.readWithoutReferences(policy, r.status, MediaLifecycle.fromCode(r.lifecycle), r.ownerUid, viewerUserId)) {
                true -> readable += r
                false -> Unit
                null -> pendingDelegated += r
            }
        }
        if (pendingDelegated.isNotEmpty()) {
            val access = ownerAccess()
            if (access != null) {
                val refsByFile = referencesOfMany(pendingDelegated.mapNotNull { it.fileId })
                // 同一引用对象只问一次
                val memo = HashMap<Pair<String, String>, Boolean>()
                for (r in pendingDelegated) {
                    val refs = refsByFile[r.fileId].orEmpty()
                    val ok = refs.any { ref ->
                        val key = ref.ownerType to ref.ownerId
                        memo.getOrPut(key) { safeCanView(access, viewerUserId, ref.ownerType, ref.ownerId) }
                    }
                    if (ok) readable += r
                }
            }
        }
        return readable.associate { r -> r.fileId!! to viewOf(r) }
    }

    override suspend fun resolveForAdmin(fileIds: Collection<String>): Map<String, MediaView> {
        val ids = fileIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val records = ids.chunked(IN_CHUNK).flatMap { chunk ->
            FileInfoTable.query { where { FileInfo::fileId `in` chunk } }.list()
        }
        return records.filter { isAdminReadable(it) }.associate { r -> r.fileId!! to adminViewOf(r) }
    }

    /** 后台读取判断（`GET /admin/infra/media/get/{fileId}` 与 [resolveForAdmin]）：未删除即可读。 */
    fun isAdminReadable(record: FileInfo): Boolean =
        MediaRules.adminReadable(record.fileId, record.status, MediaLifecycle.fromCode(record.lifecycle))

    /** 后台看到的地址：PUBLIC 与应用侧相同；其余走后台读取地址，不签发匿名可读的签名链接。 */
    fun adminUrlOf(record: FileInfo): String {
        val fileId = record.fileId!!
        return if (isPublic(record)) record.url?.takeIf { it.isNotBlank() } ?: files.relayUrlOf(fileId)
        else FileUploadLogic.adminUrlOf(fileId)
    }

    private fun adminViewOf(r: FileInfo): MediaView = MediaView(
        fileId = r.fileId!!,
        url = adminUrlOf(r),
        mimeType = r.mimeType ?: "application/octet-stream",
        size = r.size,
        businessType = r.businessType ?: "",
        lifecycle = MediaLifecycle.fromCode(r.lifecycle),
        expiresAt = null,
    )

    private fun viewOf(r: FileInfo): MediaView {
        val fileId = r.fileId!!
        val public = isPublic(r)
        val (url, exp) = if (public) {
            (r.url?.takeIf { it.isNotBlank() } ?: files.relayUrlOf(fileId)) to null
        } else {
            signer.signedUrl(files.relayUrlOf(fileId), fileId)
        }
        return MediaView(
            fileId = fileId,
            url = url,
            mimeType = r.mimeType ?: "application/octet-stream",
            size = r.size,
            businessType = r.businessType ?: "",
            lifecycle = MediaLifecycle.fromCode(r.lifecycle),
            expiresAt = exp,
        )
    }

    private suspend fun canViewAnyOwner(viewerUserId: Long?, refs: List<MediaRules.Ref>): Boolean {
        if (refs.isEmpty()) return false
        val access = ownerAccess() ?: return false
        return refs.distinctBy { it.ownerType to it.ownerId }
            .any { safeCanView(access, viewerUserId, it.ownerType, it.ownerId) }
    }

    /** 回调失败按「不可见」处理：委托判断出错时宁可拒绝，也不放行。 */
    private suspend fun safeCanView(access: MediaOwnerAccess, viewer: Long?, ownerType: String, ownerId: String): Boolean =
        try {
            access.canView(viewer, ownerType, ownerId)
        } catch (e: Exception) {
            log.warn("media.owner-access.failed", mapOf("ownerType" to ownerType, "ownerId" to ownerId), e)
            false
        }

    // ================================================================
    // 服务端转存
    // ================================================================

    override suspend fun importFromUrl(businessType: String, url: String, ownerUserId: Long): ImportResult {
        val policy = policyRegistry.policyOrNull(businessType)
            ?: return importRejected(MediaErrorCode.INVALID_BUSINESS_TYPE, "businessType '$businessType' is not registered")
        val check = MediaImportRules.checkUrl(url)
        if (!check.ok) return importRejected(check.code!!, check.message)
        val client = httpClient()
            ?: return importRejected(MediaErrorCode.NOT_AVAILABLE, "no outbound HTTP client is bound in this deployment")

        // 跟随重定向（CDN 常见 301/302 到真实存储域名）：最多 MAX_IMPORT_REDIRECTS 跳，每一跳都重新做语法检查
        // （只许 http / https），并且照样经不可信 URL 客户端发出——新主机在连接前重新解析、校验全部地址，
        // 第一跳是公网、第二跳 302 到 127.0.0.1 / 169.254.169.254 一样被拒。
        var target = url.trim()
        var redirects = 0
        val chunks = mutableListOf<ByteArray>()
        var total = 0L
        var declaredMime: String? = null
        while (true) {
            chunks.clear()
            total = 0L
            declaredMime = null
            val location: String = try {
                client.stream(
                    HttpClientRequest(
                        method = HttpClientMethod.Get,
                        url = target,
                        timeout = HttpClientTimeouts(
                            connectMillis = importConfig.connectMillis,
                            requestMillis = importConfig.requestMillis,
                            socketMillis = importConfig.socketMillis,
                        ),
                        metadata = mapOf("purpose" to "infra.media.import"),
                    ),
                ).collect { chunk ->
                    when (chunk) {
                        is HttpClientStreamChunk.Start -> {
                            declaredMime = chunk.headers["Content-Type"]
                            val declaredLength = chunk.headers["Content-Length"]?.trim()?.toLongOrNull()
                            if (declaredLength != null && declaredLength > policy.maxSizeBytes) throw ImportTooLarge()
                        }
                        is HttpClientStreamChunk.Bytes -> {
                            total += chunk.bytes.size
                            // 边读边判，超限立刻取消，不把整个大文件读进内存
                            if (total > policy.maxSizeBytes) throw ImportTooLarge()
                            chunks += chunk.bytes
                        }
                        is HttpClientStreamChunk.Text -> {
                            val b = chunk.text.encodeToByteArray()
                            total += b.size
                            if (total > policy.maxSizeBytes) throw ImportTooLarge()
                            chunks += b
                        }
                        is HttpClientStreamChunk.End -> Unit
                    }
                }
                break
            } catch (e: ImportTooLarge) {
                return importRejected(MediaErrorCode.FILE_TOO_LARGE, "remote file exceeds ${policy.maxSizeBytes} bytes")
            } catch (e: HttpClientException) {
                val refused = port.UntrustedHttpClient.rejectionCode(e.error)
                if (refused != null) {
                    log.warn(
                        "media.import.target-refused",
                        mapOf("businessType" to businessType, "code" to refused.name, "hop" to redirects, "error" to e.error.message),
                    )
                    return importRejected(refused, refused.name)
                }
                val http = e.error as? HttpClientError.Http
                val next = http?.takeIf { it.statusCode in REDIRECT_STATUSES }?.headers?.get("Location")?.trim()?.takeIf { it.isNotEmpty() }
                if (next == null) {
                    log.warn("media.import.fetch-failed", mapOf("businessType" to businessType, "hop" to redirects, "error" to e.error.message))
                    return importRejected(MediaErrorCode.FETCH_FAILED, "failed to fetch remote file")
                }
                next
            }
            if (++redirects > MAX_IMPORT_REDIRECTS) {
                log.warn("media.import.too-many-redirects", mapOf("businessType" to businessType, "url" to url))
                return importRejected(MediaErrorCode.TOO_MANY_REDIRECTS, "more than $MAX_IMPORT_REDIRECTS redirects")
            }
            val resolved = MediaImportRules.resolveRedirect(target, location)
                ?: return importRejected(MediaErrorCode.INVALID_URL, "invalid redirect target")
            val hop = MediaImportRules.checkUrl(resolved)
            if (!hop.ok) return importRejected(hop.code!!, hop.message)
            target = resolved
        }

        if (total == 0L) return importRejected(MediaErrorCode.EMPTY_FILE, "remote file is empty")
        val bytes = ByteArray(total.toInt())
        var off = 0
        for (c in chunks) {
            c.copyInto(bytes, off); off += c.size
        }
        val mime = MediaImportRules.decideMime(MediaImportRules.sniffMime(bytes), declaredMime)
        if (mime == null || mime !in policy.allowedMimeTypes) {
            return importRejected(MediaErrorCode.INVALID_MIME_TYPE, "content type ${mime ?: "unknown"} is not allowed")
        }
        val record = try {
            files.storeBytes(ownerUserId, businessType, MediaImportRules.filenameOf(target), mime, bytes)
        } catch (e: HttpException) {
            val code = MediaErrorCode.entries.firstOrNull { it.name == e.message }
                ?: MediaErrorCode.FETCH_FAILED
            return importRejected(code, e.message ?: code.name)
        }
        log.info(
            "media.import.ok",
            mapOf("fileId" to record.fileId, "businessType" to businessType, "size" to bytes.size, "owner" to ownerUserId),
        )
        return ImportResult.Imported(viewOf(record))
    }

    private class ImportTooLarge : RuntimeException()

    private fun importRejected(code: MediaErrorCode, message: String) = ImportResult.Rejected(code, message)

    // ================================================================
    // 清理
    // ================================================================

    /**
     * 清理过期的托管文件：先逐个在行锁内确认并标 deleted，再删物理对象。
     *
     * 物理删除失败不回滚 deleted 标记：行停在「deleted 且 purged_at 为空」，下一轮继续删。
     */
    suspend fun cleanupExpired(batchSize: Int = 200): CleanupReport {
        val ts = now()
        var marked = 0
        for (policy in policyRegistry.all().filter { it.managedLifecycle }) {
            val tempCutoff = ts - policy.temporaryHours.toLong() * MediaRules.HOUR_MS
            val orphanCutoff = ts - policy.orphanedDays.toLong() * MediaRules.DAY_MS
            val candidates = db.fetchAll(
                """
                SELECT file_id FROM infra_files
                WHERE business_type = :type AND status = 1 AND file_id IS NOT NULL
                  AND ((lifecycle = 'temporary' AND lifecycle_at < :tempCutoff)
                    OR (lifecycle = 'orphaned' AND lifecycle_at < :orphanCutoff))
                ORDER BY id
                LIMIT :limit
                """.trimIndent(),
                mapOf(
                    "type" to policy.businessType,
                    "tempCutoff" to tempCutoff,
                    "orphanCutoff" to orphanCutoff,
                    "limit" to batchSize.toLong(),
                ),
            ).map { it.string("file_id") }
            for (fileId in candidates) {
                if (markDeletedIfExpired(fileId)) marked++
            }
        }
        val (purged, failed) = purge(batchSize)
        if (marked > 0 || purged > 0 || failed > 0) {
            log.info("media.cleanup", mapOf("markedDeleted" to marked, "purged" to purged, "purgeFailed" to failed))
        }
        return CleanupReport(marked, purged, failed)
    }

    /** 行锁内重新确认：仍是托管类型、仍 active、无引用、已过宽限期，才标 deleted。 */
    suspend fun markDeletedIfExpired(fileId: String): Boolean = db.transaction {
        val row = lockFile(this, fileId) ?: return@transaction false
        val s = snapshotOf(row)
        val refs = countReferences(this, fileId)
        val ts = now()
        if (!MediaRules.isExpired(s.policy, s.status, s.lifecycle, row.longOrNull("lifecycle_at"), refs, ts)) {
            return@transaction false
        }
        execute(
            """
            UPDATE infra_files SET lifecycle = 'deleted', status = 0, lifecycle_at = :ts, purged_at = NULL
            WHERE file_id = :fileId
            """.trimIndent(),
            mapOf("ts" to ts, "fileId" to fileId),
        )
        true
    }

    /**
     * 删除 deleted 行的物理对象。每行一个事务：路径咨询锁内确认没有存活记录共用这条路径，
     * 再删对象并记 purged_at。删对象抛错 → 事务回滚，purged_at 仍为空，下一轮重试。
     */
    suspend fun purge(batchSize: Int = 200): Pair<Int, Int> {
        val rows = db.fetchAll(
            """
            SELECT id, path FROM infra_files
            WHERE lifecycle = 'deleted' AND purged_at IS NULL
            ORDER BY id
            LIMIT :limit
            """.trimIndent(),
            mapOf("limit" to batchSize.toLong()),
        )
        var purged = 0
        var failed = 0
        for (r in rows) {
            val id = r.long("id")
            val path = r.string("path")
            try {
                db.transaction {
                    FileUploadLogic.lockStoragePath(this, path)
                    val shared = fetchAll(
                        "SELECT 1 AS ok FROM infra_files WHERE path = :path AND status = 1 LIMIT 1",
                        mapOf("path" to path),
                    ).isNotEmpty()
                    if (!shared) files.deleteObject(path)
                    execute(
                        "UPDATE infra_files SET purged_at = :ts WHERE id = :id AND lifecycle = 'deleted'",
                        mapOf("ts" to now(), "id" to id),
                    )
                }
                purged++
            } catch (e: Exception) {
                failed++
                log.warn("media.purge.failed", mapOf("id" to id, "path" to path), e)
            }
        }
        return purged to failed
    }

    /** 后台列表用：每个 fileId 的引用数。 */
    suspend fun referenceCounts(fileIds: Collection<String>): Map<String, Long> {
        val ids = fileIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>()
        for (chunk in ids.chunked(IN_CHUNK)) {
            val (clause, params) = inClause("f", chunk)
            db.fetchAll(
                "SELECT file_id, COUNT(*) AS c FROM infra_media_references WHERE file_id IN ($clause) GROUP BY file_id",
                params,
            ).forEach { out[it.string("file_id")] = it.long("c") }
        }
        return out
    }

    // ================================================================
    // SQL 辅助
    // ================================================================

    private suspend fun lockFile(tx: DbContext, fileId: String): Row? =
        tx.fetchAll(
            """
            SELECT file_id, owner_uid, business_type, status, lifecycle, lifecycle_at
            FROM infra_files WHERE file_id = :fileId
            FOR UPDATE
            """.trimIndent(),
            mapOf("fileId" to fileId),
        ).firstOrNull()

    private fun snapshotOf(row: Row): MediaRules.FileSnapshot = MediaRules.FileSnapshot(
        status = row.intOrNull("status") ?: 0,
        lifecycle = MediaLifecycle.fromCode(row.stringOrNull("lifecycle")),
        uploaderUid = row.longOrNull("owner_uid"),
        policy = row.stringOrNull("business_type")?.let { policyRegistry.policyOrNull(it) },
    )

    private suspend fun referencesOf(tx: DbContext, fileId: String): List<MediaRules.Ref> =
        tx.fetchAll(
            "SELECT owner_type, owner_id, usage FROM infra_media_references WHERE file_id = :fileId ORDER BY id",
            mapOf("fileId" to fileId),
        ).map { MediaRules.Ref(it.string("owner_type"), it.string("owner_id"), it.string("usage")) }

    private suspend fun referencesOfMany(fileIds: List<String>): Map<String, List<MediaRules.Ref>> {
        val out = HashMap<String, MutableList<MediaRules.Ref>>()
        for (chunk in fileIds.distinct().chunked(IN_CHUNK)) {
            val (clause, params) = inClause("f", chunk)
            db.fetchAll(
                "SELECT file_id, owner_type, owner_id, usage FROM infra_media_references WHERE file_id IN ($clause) ORDER BY id",
                params,
            ).forEach {
                out.getOrPut(it.string("file_id")) { mutableListOf() } +=
                    MediaRules.Ref(it.string("owner_type"), it.string("owner_id"), it.string("usage"))
            }
        }
        return out
    }

    private suspend fun countReferences(tx: DbContext, fileId: String): Long =
        tx.fetchAll(
            "SELECT COUNT(*) AS c FROM infra_media_references WHERE file_id = :fileId",
            mapOf("fileId" to fileId),
        ).first().long("c")

    private suspend fun setLifecycle(tx: DbContext, fileId: String, next: MediaLifecycle, ts: Long) {
        tx.execute(
            "UPDATE infra_files SET lifecycle = :lifecycle, lifecycle_at = :ts WHERE file_id = :fileId",
            mapOf("lifecycle" to next.code, "ts" to ts, "fileId" to fileId),
        )
    }

    private fun invalidArgument(vararg values: String): AttachResult.Rejected? {
        val limits = intArrayOf(64, 64, 128, 64)
        values.forEachIndexed { i, v ->
            if (v.isBlank() || v.length > limits[i]) {
                return AttachResult.Rejected(MediaErrorCode.INVALID_ARGUMENT, "fileId/ownerType/ownerId/usage must be non-blank and within length limits")
            }
        }
        return null
    }

    companion object {
        private const val IN_CHUNK = 200

        /** 转存最多跟随的重定向跳数。 */
        private const val MAX_IMPORT_REDIRECTS = 5

        /** 跟随的重定向状态码（GET 原样重发）。 */
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        /** 生成 `:f0, :f1, …` 占位符与参数表；值走参数绑定，不拼进 SQL。 */
        fun inClause(prefix: String, values: List<String>): Pair<String, Map<String, Any?>> {
            val params = LinkedHashMap<String, Any?>()
            val clause = values.mapIndexed { i, v ->
                val name = "$prefix$i"
                params[name] = v
                ":$name"
            }.joinToString(", ")
            return clause to params
        }
    }
}
