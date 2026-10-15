package logic

import init.generated.InfraMigrationResources
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import neton.core.config.getEnv
import neton.core.module.MigrationDialect
import neton.database.adapter.sqlx.SqlxDatabase
import neton.database.api.DbContext
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver
import neton.database.dbContext
import neton.database.migration.MigrationCommand
import neton.database.migration.MigrationConfig
import neton.database.migration.MigrationEngine
import neton.database.migration.MigrationResult
import neton.logging.Fields
import neton.logging.Logger
import neton.storage.FileEntry
import neton.storage.FileStat
import neton.storage.ListOptions
import neton.storage.StorageOperator
import neton.storage.WriteOptions
import port.AttachResult
import port.ImportResult
import port.MediaErrorCode
import port.MediaLifecycle
import port.MediaOwnerAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration

/**
 * 媒体生命周期与引用登记的真实数据库契约（infra-media §6 / §7）。
 *
 * 与 `event.PostgresDomainEventStoreDbTest` 同一套跑法：由 `postgresContractTest` 任务运行，
 * 需要 `EVENT_DB_URI`（角色须有 `CREATEDB`）；每个用例在临时库里跑 infra 真实迁移后执行。
 */
class MediaLogicDbTest {

    private object Env {
        val uri: String? = getEnv("EVENT_DB_URI")?.takeIf { it.isNotBlank() }
    }

    private fun withMigratedDatabase(block: suspend (DbContext) -> Unit) {
        val adminUri = Env.uri ?: fail("EVENT_DB_URI 未设置；由 postgresContractTest 任务提供。")
        val tempDb = "mediatest_" + neton.security.random.SecureRandom.hex(6)
        val tempUri = withDatabaseName(adminUri, tempDb)
        onDatabase(adminUri) { it.execute("CREATE DATABASE $tempDb") }
        try {
            onDatabase(tempUri) { db ->
                val engine = MigrationEngine(db, MigrationConfig(dialect = MigrationDialect.POSTGRESQL))
                when (val r = engine.run(MigrationCommand.UP, InfraMigrationResources.sources)) {
                    is MigrationResult.Up -> if (!r.ok) fail("infra 迁移失败于 ${r.failedAt}")
                    else -> fail("非预期的迁移结果: $r")
                }
                block(db)
            }
        } finally {
            onDatabase(adminUri) {
                it.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = :d AND pid <> pg_backend_pid()",
                    mapOf("d" to tempDb),
                )
                it.execute("DROP DATABASE IF EXISTS $tempDb")
            }
        }
    }

    private fun withDatabaseName(uri: String, database: String): String {
        val queryStart = uri.indexOf('?').let { if (it < 0) uri.length else it }
        val base = uri.substring(0, queryStart)
        val slash = base.lastIndexOf('/')
        return base.substring(0, slash + 1) + database + uri.substring(queryStart)
    }

    private fun onDatabase(uri: String, block: suspend (DbContext) -> Unit) {
        SqlxDatabase.initialize(DatabaseConfig(driver = DatabaseDriver.POSTGRESQL, uri = uri))
        try {
            runBlocking { block(dbContext()) }
        } finally {
            runBlocking { SqlxDatabase.close() }
        }
    }

    // ---- 装配 ----

    private object QuietLogger : Logger {
        override fun trace(msg: String, fields: Fields) {}
        override fun debug(msg: String, fields: Fields) {}
        override fun info(msg: String, fields: Fields) {}
        override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
        override fun error(msg: String, fields: Fields, cause: Throwable?) {}
    }

    private class MemoryStorage : StorageOperator {
        val objects = HashMap<String, ByteArray>()
        var failDeletes = false
        override val scheme = "memory"
        override val name = "test"
        override suspend fun write(path: String, data: ByteArray, options: WriteOptions) { objects[path] = data }
        override suspend fun read(path: String): ByteArray = objects[path] ?: error("missing $path")
        override suspend fun delete(path: String) {
            if (failDeletes) error("storage down")
            objects.remove(path)
        }
        override suspend fun exists(path: String) = path in objects
        override suspend fun stat(path: String): FileStat = throw UnsupportedOperationException()
        override suspend fun list(path: String, options: ListOptions): List<FileEntry> = emptyList()
        override suspend fun copy(src: String, dst: String) { objects[dst] = objects[src]!! }
        override suspend fun move(src: String, dst: String) { objects[dst] = objects.remove(src)!! }
        override suspend fun presignRead(path: String, ttl: Duration): String = throw UnsupportedOperationException()
        override suspend fun presignWrite(path: String, ttl: Duration): String = throw UnsupportedOperationException()
    }

    private class Fixture(db: DbContext) {
        val storage = MemoryStorage()
        val registry = FileBusinessPolicyRegistry.default().apply {
            register(
                FileBusinessPolicy(
                    businessType = "content.image",
                    access = MediaAccess.DELEGATED,
                    allowedMimeTypes = setOf("image/png"),
                    maxSizeBytes = 1024 * 1024,
                    usages = setOf("content.image"),
                    reusable = false,
                    managedLifecycle = true,
                    assetDir = "content-images",
                ),
            )
        }
        val files = FileUploadLogic(QuietLogger, storage, registry, db)
        var visible: Set<Pair<String, String>> = emptySet()
        var access: MediaOwnerAccess? = MediaOwnerAccess { _, t, id -> (t to id) in visible }
        var clockSeconds = 1_000_000L
        val media = MediaLogic(
            log = QuietLogger,
            db = db,
            policyRegistry = registry,
            files = files,
            signer = MediaUrlSigner(MediaUrlSigner.deriveKey("k".repeat(40)), nowSeconds = { clockSeconds }),
            ownerAccess = { access },
            httpClient = { null },
        )
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    private suspend fun DbContext.lifecycleOf(fileId: String): String =
        fetchAll("SELECT lifecycle FROM infra_files WHERE file_id = :f", mapOf("f" to fileId)).first().string("lifecycle")

    private suspend fun DbContext.age(fileId: String, millis: Long) {
        execute(
            "UPDATE infra_files SET lifecycle_at = lifecycle_at - :m WHERE file_id = :f",
            mapOf("m" to millis, "f" to fileId),
        )
    }

    private suspend fun DbContext.refCount(fileId: String): Long =
        fetchAll("SELECT COUNT(*) AS c FROM infra_media_references WHERE file_id = :f", mapOf("f" to fileId)).first().long("c")

    // ---- 用例 ----

    /** 内置类型：上传即 attached，清理永远不碰。 */
    @Test
    fun builtinTypesStayAttachedAndAreNeverCleaned() = withMigratedDatabase { db ->
        val fx = Fixture(db)
        val f = fx.files.storeBytes(7, "generic_image", "a.png", "image/png", png)
        assertEquals("attached", db.lifecycleOf(f.fileId!!))
        db.execute("UPDATE infra_files SET lifecycle_at = 0 WHERE file_id = :f", mapOf("f" to f.fileId))
        val report = fx.media.cleanupExpired()
        assertEquals(0, report.markedDeleted)
        assertEquals("attached", db.lifecycleOf(f.fileId!!))
        assertTrue(fx.storage.objects.containsKey(f.path))
    }

    @Test
    fun attachDetachLifecycle() = withMigratedDatabase { db ->
        val fx = Fixture(db)
        val f = fx.files.storeBytes(7, "content.image", "a.png", "image/png", png)
        val id = f.fileId!!
        assertEquals("temporary", db.lifecycleOf(id))

        // 同一上传者再次上传同内容 → 复用临时记录
        assertEquals(id, fx.files.storeBytes(7, "content.image", "a.png", "image/png", png).fileId)

        val notOwner = fx.media.attach(id, "content.item", "1", "content.image", actorUserId = 8)
        assertEquals(MediaErrorCode.NOT_FILE_OWNER, assertIs<AttachResult.Rejected>(notOwner).code)
        val badUsage = fx.media.attach(id, "content.item", "1", "comment.image", actorUserId = 7)
        assertEquals(MediaErrorCode.USAGE_NOT_ALLOWED, assertIs<AttachResult.Rejected>(badUsage).code)
        assertEquals(
            MediaErrorCode.FILE_NOT_FOUND,
            assertIs<AttachResult.Rejected>(fx.media.attach("file_nope", "content.item", "1", "content.image", 7)).code,
        )

        val ok = assertIs<AttachResult.Attached>(fx.media.attach(id, "content.item", "1", "content.image", 7))
        assertFalse(ok.alreadyAttached)
        assertEquals("attached", db.lifecycleOf(id))
        assertTrue(assertIs<AttachResult.Attached>(fx.media.attach(id, "content.item", "1", "content.image", 7)).alreadyAttached)
        assertEquals(1L, db.refCount(id))

        // 不可复用：另一个对象再引用被拒
        val reuse = fx.media.attach(id, "content.item", "2", "content.image", 7)
        assertEquals(MediaErrorCode.NOT_REUSABLE, assertIs<AttachResult.Rejected>(reuse).code)

        // 已关联后同内容再上传 → 新的临时记录，不挂到旧引用上
        val second = fx.files.storeBytes(7, "content.image", "a.png", "image/png", png)
        assertTrue(second.fileId != id)
        assertEquals("temporary", db.lifecycleOf(second.fileId!!))

        assertTrue(fx.media.detach(id, "content.item", "1", "content.image"))
        assertFalse(fx.media.detach(id, "content.item", "1", "content.image"))
        assertEquals("orphaned", db.lifecycleOf(id))

        // 宽限期内重新 attach
        assertIs<AttachResult.Attached>(fx.media.attach(id, "content.item", "3", "content.image", 7))
        assertEquals("attached", db.lifecycleOf(id))
        assertEquals(1, fx.media.detachAll("content.item", "3"))
        assertEquals("orphaned", db.lifecycleOf(id))
    }

    @Test
    fun cleanupDeletesExpiredAndAttachRefusesAfterwards() = withMigratedDatabase { db ->
        val fx = Fixture(db)
        val temp = fx.files.storeBytes(7, "content.image", "a.png", "image/png", png).fileId!!
        val fresh = fx.files.storeBytes(8, "content.image", "b.png", "image/png", png + byteArrayOf(9)).fileId!!
        db.age(temp, 25 * MediaRules.HOUR_MS)

        val r = fx.media.cleanupExpired()
        assertEquals(1, r.markedDeleted)
        assertEquals(1, r.purged)
        assertEquals("deleted", db.lifecycleOf(temp))
        assertEquals("temporary", db.lifecycleOf(fresh))

        val attach = fx.media.attach(temp, "content.item", "1", "content.image", 7)
        assertEquals(MediaErrorCode.FILE_DELETED, assertIs<AttachResult.Rejected>(attach).code)
        assertTrue(fx.media.resolve(listOf(temp), 7).isEmpty(), "已删除的文件不展开")

        // 孤儿过宽限期
        assertIs<AttachResult.Attached>(fx.media.attach(fresh, "content.item", "9", "content.image", 8))
        fx.media.detach(fresh, "content.item", "9", "content.image")
        db.age(fresh, 6 * MediaRules.DAY_MS)
        assertEquals(0, fx.media.cleanupExpired().markedDeleted, "孤儿未过 7 天")
        db.age(fresh, 2 * MediaRules.DAY_MS)
        assertEquals(1, fx.media.cleanupExpired().markedDeleted)
    }

    /** 内容寻址：两条记录共用一个对象，删掉其中一条不能删对象。 */
    @Test
    fun sharedObjectSurvivesWhileAnotherRowIsLive() = withMigratedDatabase { db ->
        val fx = Fixture(db)
        val a = fx.files.storeBytes(7, "content.image", "a.png", "image/png", png)
        val b = fx.files.storeBytes(8, "content.image", "a.png", "image/png", png)
        assertEquals(a.path, b.path)
        db.age(a.fileId!!, 25 * MediaRules.HOUR_MS)
        assertEquals(1, fx.media.cleanupExpired().markedDeleted)
        assertTrue(fx.storage.objects.containsKey(a.path), "b 仍存活，对象必须保留")

        db.age(b.fileId!!, 25 * MediaRules.HOUR_MS)
        fx.media.cleanupExpired()
        assertFalse(fx.storage.objects.containsKey(a.path))
    }

    @Test
    fun failedStorageDeleteIsRetried() = withMigratedDatabase { db ->
        val fx = Fixture(db)
        val f = fx.files.storeBytes(7, "content.image", "a.png", "image/png", png)
        db.age(f.fileId!!, 25 * MediaRules.HOUR_MS)
        fx.storage.failDeletes = true
        val r1 = fx.media.cleanupExpired()
        assertEquals(1, r1.markedDeleted)
        assertEquals(1, r1.purgeFailed)
        assertEquals("deleted", db.lifecycleOf(f.fileId!!))
        assertNull(db.fetchAll("SELECT purged_at FROM infra_files WHERE file_id = :f", mapOf("f" to f.fileId)).first().longOrNull("purged_at"))

        fx.storage.failDeletes = false
        val r2 = fx.media.cleanupExpired()
        assertEquals(1, r2.purged)
        assertFalse(fx.storage.objects.containsKey(f.path))
    }

    /** attach 与清理并发：结果要么「已关联且未删」，要么「已删且 attach 被拒」，不会出现已删却有引用。 */
    @Test
    fun attachAndCleanupAreMutuallyExclusive() = withMigratedDatabase { db ->
        val fx = Fixture(db)
        repeat(10) { i ->
            val f = fx.files.storeBytes(7, "content.image", "a.png", "image/png", png + byteArrayOf(i.toByte()))
            val id = f.fileId!!
            db.age(id, 25 * MediaRules.HOUR_MS)
            val (attach, deleted) = coroutineScope {
                val a = async { fx.media.attach(id, "content.item", "c$i", "content.image", 7) }
                val d = async { fx.media.markDeletedIfExpired(id) }
                a.await() to d.await()
            }
            val lifecycle = db.lifecycleOf(id)
            if (deleted) {
                assertEquals("deleted", lifecycle)
                assertEquals(MediaErrorCode.FILE_DELETED, assertIs<AttachResult.Rejected>(attach).code)
                assertEquals(0L, db.refCount(id))
            } else {
                assertIs<AttachResult.Attached>(attach)
                assertEquals("attached", lifecycle)
                assertEquals(1L, db.refCount(id))
            }
        }
    }

    @Test
    fun resolveAppliesAccessAndSignsNonPublic() = withMigratedDatabase { db ->
        val fx = Fixture(db)
        val delegated = fx.files.storeBytes(7, "content.image", "a.png", "image/png", png).fileId!!
        val public = fx.files.storeBytes(7, "generic_image", "a.png", "image/png", png).fileId!!
        val owner = fx.files.storeBytes(7, "kyc_document", "a.png", "image/png", png).fileId!!

        // 临时 DELEGATED：只有上传者
        assertEquals(setOf(delegated, public, owner), fx.media.resolve(listOf(delegated, public, owner), 7).keys)
        assertEquals(setOf(public), fx.media.resolve(listOf(delegated, public, owner), 8).keys)
        assertEquals(setOf(public), fx.media.resolve(listOf(delegated, public, owner), null).keys)

        fx.media.attach(delegated, "content.item", "1", "content.image", 7)
        assertEquals(setOf(public), fx.media.resolve(listOf(delegated, public), 8).keys, "引用对象不可见")
        fx.visible = setOf("content.item" to "1")
        val views = fx.media.resolve(listOf(delegated, public), 8)
        val dv = assertNotNull(views[delegated])
        assertEquals(MediaLifecycle.ATTACHED, dv.lifecycle)
        assertNotNull(dv.expiresAt)
        assertTrue(dv.url.startsWith("/app/infra/file/get/$delegated?exp="), dv.url)
        val exp = dv.url.substringAfter("exp=").substringBefore('&').toLong()
        val sig = dv.url.substringAfter("sig=")
        assertTrue(fx.media.verifySignature(delegated, exp, sig))
        assertNull(views[public]!!.expiresAt, "公开文件是长期地址")

        val delRecord = fx.files.getByFileId(delegated)!!
        assertTrue(fx.media.canRead(delRecord, 8))
        fx.access = null
        assertFalse(fx.media.canRead(delRecord, 8), "没有绑定委托回调 → 拒绝")
        assertTrue(fx.media.canRead(delRecord, 7), "上传者仍可读")

        // IN 占位符超过 10 个（:f1 与 :f10 不能互相吞）
        val many = (0 until 12).map { fx.files.storeBytes(7, "generic_image", "x.png", "image/png", png + byteArrayOf(it.toByte(), 1)).fileId!! }
        assertEquals(many.toSet(), fx.media.resolve(many, null).keys)
        many.take(11).forEachIndexed { i, id ->
            assertIs<AttachResult.Attached>(fx.media.attach(id, "member", "$i", "generic_image", 7))
        }
        val counts = fx.media.referenceCounts(many)
        assertEquals(11, counts.size)
        assertTrue(counts.values.all { it == 1L })
        assertEquals("attached", db.lifecycleOf(many[0]), "内置类型 attach 后仍是 attached")
    }

    /** 后台上传：上传者记在 admin_uploader_id，应用侧同号账号不是上传者；只能以 null 关联。 */
    @Test
    fun adminUploadIsAttachedBySystemActorOnlyAndResolvesForAdmin() = withMigratedDatabase { db ->
        val fx = Fixture(db)
        val upload = object : neton.core.http.UploadFile {
            override val fieldName = "file"
            override val filename = "a.png"
            override val contentType: String? = "image/png"
            override val size = png.size.toLong()
            override suspend fun bytes() = png
        }
        val f = fx.files.uploadAsAdmin(7, "content.image", upload)
        val id = f.fileId!!
        val row = db.fetchAll(
            "SELECT owner_uid, admin_uploader_id, lifecycle FROM infra_files WHERE file_id = :f",
            mapOf("f" to id),
        ).first()
        assertNull(row.longOrNull("owner_uid"))
        assertEquals(7L, row.longOrNull("admin_uploader_id"))
        assertEquals("temporary", row.string("lifecycle"))

        // 同一管理员再传同内容 → 复用临时记录；同号应用账号上传 → 另一条记录
        assertEquals(id, fx.files.uploadAsAdmin(7, "content.image", upload).fileId)
        val appSide = fx.files.storeBytes(7, "content.image", "a.png", "image/png", png).fileId!!
        assertTrue(appSide != id)
        assertEquals(id, fx.files.uploadAsAdmin(7, "content.image", upload).fileId, "应用账号的行不被后台去重命中")

        // 应用侧：同号账号既读不到也关联不了
        assertTrue(fx.media.resolve(listOf(id), 7).isEmpty())
        assertFalse(fx.media.canRead(fx.files.getByFileId(id)!!, 7))
        assertEquals(
            MediaErrorCode.NOT_FILE_OWNER,
            assertIs<AttachResult.Rejected>(fx.media.attach(id, "content.item", "1", "content.image", 7)).code,
        )

        // 后台展开：临时文件也给后台读取地址，不签名
        val adminView = assertNotNull(fx.media.resolveForAdmin(listOf(id, "file_nope"))[id])
        assertEquals("/admin/infra/media/get/$id", adminView.url)
        assertNull(adminView.expiresAt)
        assertEquals(MediaLifecycle.TEMPORARY, adminView.lifecycle)

        // 后台保存路径（actor = null）关联成功
        assertIs<AttachResult.Attached>(fx.media.attach(id, "content.item", "1", "content.image", null))
        assertEquals("attached", db.lifecycleOf(id))
        assertTrue(fx.media.resolve(listOf(id), 8).isEmpty(), "引用对象不可见")
        fx.visible = setOf("content.item" to "1")
        assertEquals(setOf(id), fx.media.resolve(listOf(id), 8).keys, "可见后按引用委托放行")

        // 公开类型：后台展开与应用侧相同
        val pub = fx.files.uploadAsAdmin(7, "generic_image", upload).fileId!!
        assertNull(fx.files.getByFileId(pub)!!.ownerUid)
        assertEquals(fx.media.resolve(listOf(pub), null)[pub]!!.url, fx.media.resolveForAdmin(listOf(pub))[pub]!!.url)

        // 清理后后台也读不到
        fx.media.detachAll("content.item", "1")
        db.age(id, 8 * MediaRules.DAY_MS)
        fx.media.cleanupExpired()
        assertTrue(fx.media.resolveForAdmin(listOf(id)).isEmpty())
        assertFalse(fx.media.isAdminReadable(fx.files.getByFileId(id)!!))
    }

    @Test
    fun importWithoutHttpClientIsNotAvailable() = withMigratedDatabase { db ->
        val fx = Fixture(db)
        val r = fx.media.importFromUrl("content.image", "https://example.com/a.png", 7)
        assertEquals(MediaErrorCode.NOT_AVAILABLE, assertIs<ImportResult.Rejected>(r).code)
        val bad = fx.media.importFromUrl("content.image", "http://127.0.0.1/a.png", 7)
        assertEquals(MediaErrorCode.FORBIDDEN_HOST, assertIs<ImportResult.Rejected>(bad).code)
    }
}
