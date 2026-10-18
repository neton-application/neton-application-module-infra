package event

import init.generated.InfraMigrationResources
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import neton.core.config.getEnv
import neton.core.event.PendingEventRecord
import logic.DomainEventOpsLogic
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import port.InfraErrorCodes
import neton.logging.Fields
import neton.logging.Logger
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `PostgresDomainEventStore` 的真实数据库契约测试。
 *
 * 由 `postgresContractTest` 任务运行，不在普通 `macosArm64Test` 里；需要 `EVENT_DB_URI`
 * （角色须有 `CREATEDB`），未设置即失败。每个用例在随机命名的临时数据库里跑 infra 的真实
 * 迁移后执行，`finally` 里 drop 临时库并关连接。
 *
 * 覆盖 FakeStore 单测验不了的契约：`SKIP LOCKED` 领取互斥、`claim_token` fencing、
 * 事务边界、滞留回收、终态与退避。设计取舍见 spec/event.md §9.1。
 */
class PostgresDomainEventStoreDbTest {

    // ---- 环境与生命周期 ----

    private object Env {
        val uri: String? = getEnv("EVENT_DB_URI")?.takeIf { it.isNotBlank() }
    }

    /** 建临时库 → 跑真实迁移 → 执行 → 无论成败 drop 临时库并关连接。 */
    private fun withMigratedDatabase(block: suspend (DbContext) -> Unit) {
        val adminUri = Env.uri ?: fail("EVENT_DB_URI 未设置；由 postgresContractTest 任务提供。")
        val tempDb = "evtest_" + neton.security.random.SecureRandom.hex(6)
        val tempUri = withDatabaseName(adminUri, tempDb)

        onDatabase(adminUri) { it.execute("CREATE DATABASE $tempDb") }
        try {
            onDatabase(tempUri) { db ->
                runInfraMigrations(db)
                block(db)
            }
        } finally {
            onDatabase(adminUri) {
                it.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                        "WHERE datname = :d AND pid <> pg_backend_pid()",
                    mapOf("d" to tempDb),
                )
                it.execute("DROP DATABASE IF EXISTS $tempDb")
            }
        }
    }

    /** 替换 URI 里的库名，保留 `?sslmode=…` 之类的查询参数。 */
    private fun withDatabaseName(uri: String, database: String): String {
        val queryStart = uri.indexOf('?').let { if (it < 0) uri.length else it }
        val base = uri.substring(0, queryStart)
        val query = uri.substring(queryStart)
        val slash = base.lastIndexOf('/')
        require(slash > "postgresql://".length) { "EVENT_DB_URI 缺少数据库名: $uri" }
        return base.substring(0, slash + 1) + database + query
    }

    /** 打开一个连接、执行、必定关闭。SqlxDatabase 是进程单例，每段用完都得 close 才能换库。 */
    private fun onDatabase(uri: String, block: suspend (DbContext) -> Unit) {
        SqlxDatabase.initialize(DatabaseConfig(driver = DatabaseDriver.POSTGRESQL, uri = uri))
        try {
            runBlocking { block(dbContext()) }
        } finally {
            runBlocking { SqlxDatabase.close() }
        }
    }

    /** 跑 infra 的真实迁移；再确认迁移出来的表带 V010 的 claim_token 列。 */
    private suspend fun runInfraMigrations(db: DbContext) {
        val engine = MigrationEngine(db, MigrationConfig(dialect = MigrationDialect.POSTGRESQL))
        when (val result = engine.run(MigrationCommand.UP, InfraMigrationResources.sources)) {
            is MigrationResult.Up -> {
                if (!result.ok) fail("infra 迁移失败于 ${result.failedAt}")
                assertTrue(result.applied.isNotEmpty(), "临时库里应当应用了迁移")
            }
            is MigrationResult.Aborted -> fail("infra 迁移中止: ${result.reason}")
            else -> fail("非预期的迁移结果: $result")
        }
        val cols = db.fetchAll(
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'infra_domain_events'
            """.trimIndent(),
        ).mapNotNull { it.stringOrNull("column_name") }.toSet()
        assertTrue("claim_token" in cols, "迁移后 infra_domain_events 缺少 claim_token 列（V010 未生效？）实际列: $cols")
    }

    private fun store(db: DbContext, clock: () -> Long = { 1_000L }) =
        PostgresDomainEventStore(db, now = clock)

    private fun pending(i: Int) = PendingEventRecord("event.Sample", "L", "payload-$i")

    private suspend fun DbContext.statusOf(id: Long): Int =
        fetchAll("SELECT status FROM infra_domain_events WHERE id = :id", mapOf("id" to id)).first().intOrNull("status")!!

    private suspend fun DbContext.countByStatus(status: Int): Long =
        fetchAll("SELECT COUNT(*) AS c FROM infra_domain_events WHERE status = :s", mapOf("s" to status)).first().longOrNull("c")!!

    // ---- 事务边界 ----

    /** append 必须在事务里。 */
    @Test
    fun appendOutsideTransactionIsRejected() = withMigratedDatabase { db ->
        val e = assertFailsWith<IllegalStateException> { store(db).append(pending(1)) }
        assertTrue(e.message!!.contains("事务"), e.message!!)
        assertEquals(0L, db.countByStatus(0), "被拒绝的 append 不能留下行")
    }

    /** 与业务同事务：回滚则行消失，提交则行存在。 */
    @Test
    fun appendRollsBackWithEnclosingTransaction() = withMigratedDatabase { db ->
        val s = store(db)
        assertFailsWith<IllegalStateException> {
            db.transaction {
                s.append(pending(1))
                throw IllegalStateException("business failed after publish")
            }
        }
        assertEquals(0L, db.countByStatus(0), "事务回滚后 outbox 行必须消失")

        db.transaction { s.append(pending(2)) }
        assertEquals(1L, db.countByStatus(0), "事务提交后 outbox 行必须存在")
    }

    // ---- 领取互斥：SKIP LOCKED ----

    /** 两个领取者并发领取，id 集合互不重叠、并集覆盖全部。 */
    @Test
    fun concurrentClaimantsNeverOverlap() = withMigratedDatabase { db ->
        val s = store(db)
        db.transaction { repeat(50) { s.append(pending(it)) } }

        val (a, b) = coroutineScope {
            val a = async { s.claimDue(now = 2_000, limit = 30, staleBefore = 0) }
            val b = async { s.claimDue(now = 2_000, limit = 30, staleBefore = 0) }
            a.await() to b.await()
        }
        val idsA = a.map { it.id }.toSet()
        val idsB = b.map { it.id }.toSet()

        assertTrue(idsA.intersect(idsB).isEmpty(), "两个领取者拿到了同一条: ${idsA.intersect(idsB)}")
        assertEquals(50, (idsA + idsB).size, "并集必须覆盖全部 50 条")
        assertEquals(50L, db.countByStatus(1), "领取后全部处于投递中")
        assertEquals(1, a.map { it.claimToken }.toSet().size, "同一批令牌一致")
        assertEquals(1, b.map { it.claimToken }.toSet().size)
        assertTrue(a.first().claimToken != b.first().claimToken, "两批令牌不同")
    }

    // ---- 滞留回收 ----

    /** 投递中的记录超过可见性超时后被重新领取，且换令牌。 */
    @Test
    fun staleInFlightRowsAreReclaimed() = withMigratedDatabase { db ->
        val s = store(db)
        db.transaction { s.append(pending(1)) }

        val first = s.claimDue(now = 1_000, limit = 10, staleBefore = 0)
        assertEquals(1, first.size)
        // 模拟领取者崩溃：不落定

        assertTrue(s.claimDue(now = 1_500, limit = 10, staleBefore = 500).isEmpty(), "未超时不能重领")

        val second = s.claimDue(now = 10_000, limit = 10, staleBefore = 5_000)
        assertEquals(1, second.size)
        assertEquals(first[0].id, second[0].id)
        assertTrue(first[0].claimToken != second[0].claimToken, "重领必须换令牌")
    }

    // ---- fencing：迟到的旧领取者不能覆盖新领取者 ----

    /** A 领取后超时，B 重领并成功；A 迟到的 markFailed 不能把 B 的成功改成待重试。 */
    @Test
    fun staleClaimantCannotOverrideDelivered() = withMigratedDatabase { db ->
        val s = store(db)
        db.transaction { s.append(pending(1)) }

        val a = s.claimDue(now = 1_000, limit = 10, staleBefore = 0).single()
        val b = s.claimDue(now = 10_000, limit = 10, staleBefore = 5_000).single()
        assertTrue(s.markDelivered(b.id, b.claimToken, 10_100), "B 的落定必须成功")
        assertEquals(2, db.statusOf(b.id))

        assertTrue(!s.markFailed(a.id, a.claimToken, 10_200, 20_000, "late"), "A 的落定必须作废")
        assertEquals(2, db.statusOf(b.id), "状态仍是已投递，没被 A 改回待重试")
    }

    /** 反向：A 超时，B 重领后失败重排；A 迟到的 markDelivered 不能把它改成已投递。 */
    @Test
    fun staleClaimantCannotOverrideRetry() = withMigratedDatabase { db ->
        val s = store(db)
        db.transaction { s.append(pending(1)) }

        val a = s.claimDue(now = 1_000, limit = 10, staleBefore = 0).single()
        val b = s.claimDue(now = 10_000, limit = 10, staleBefore = 5_000).single()
        assertTrue(s.markFailed(b.id, b.claimToken, 10_100, 20_000, "transient"))
        assertEquals(0, db.statusOf(b.id), "B 失败后回到待投递")

        assertTrue(!s.markDelivered(a.id, a.claimToken, 10_200), "A 的成功落定必须作废")
        assertEquals(0, db.statusOf(b.id), "仍是待投递，没被 A 改成已投递")
    }

    /** 令牌正确但状态已不是投递中，同样拒绝——CAS 同时看状态和令牌。 */
    @Test
    fun settlementRequiresDeliveringStatus() = withMigratedDatabase { db ->
        val s = store(db)
        db.transaction { s.append(pending(1)) }
        val a = s.claimDue(now = 1_000, limit = 10, staleBefore = 0).single()
        assertTrue(s.markDelivered(a.id, a.claimToken, 1_100))
        assertTrue(!s.markDelivered(a.id, a.claimToken, 1_200))
        assertTrue(!s.markFailed(a.id, a.claimToken, 1_300, null, "x"))
    }

    // ---- 终态与退避 ----

    @Test
    fun permanentFailureIsTerminal() = withMigratedDatabase { db ->
        val s = store(db)
        db.transaction { s.append(pending(1)) }
        val a = s.claimDue(now = 1_000, limit = 10, staleBefore = 0).single()
        assertTrue(s.markFailed(a.id, a.claimToken, 1_100, nextAttemptAt = null, error = "poison"))
        assertEquals(3, db.statusOf(a.id))
        assertTrue(s.claimDue(now = 100_000, limit = 10, staleBefore = 99_000).isEmpty(), "终态不被领取也不被回收")
    }

    @Test
    fun retryHonoursNextAttemptAt() = withMigratedDatabase { db ->
        val s = store(db)
        db.transaction { s.append(pending(1)) }
        val a = s.claimDue(now = 1_000, limit = 10, staleBefore = 0).single()
        assertTrue(s.markFailed(a.id, a.claimToken, 1_100, nextAttemptAt = 5_000, error = "transient"))

        assertTrue(s.claimDue(now = 4_999, limit = 10, staleBefore = 0).isEmpty(), "未到重试时刻不领")
        val again = s.claimDue(now = 5_000, limit = 10, staleBefore = 0)
        assertEquals(1, again.size)
        assertEquals(1, again[0].attempts, "attempts 已累加")
    }

    // ---- 运维面：DomainEventOpsLogic 在真实表上的行为 ----

    private object QuietLogger : Logger {
        override fun trace(msg: String, fields: Fields) {}
        override fun debug(msg: String, fields: Fields) {}
        override fun info(msg: String, fields: Fields) {}
        override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
        override fun error(msg: String, fields: Fields, cause: Throwable?) {}
    }

    private fun ops(db: DbContext) = DomainEventOpsLogic(QuietLogger, db)

    @Test
    fun statsCountEveryStatusAndAges() = withMigratedDatabase { db ->
        val s = store(db, clock = { 1_000L })
        db.transaction { repeat(3) { s.append(pending(it)) } }              // 3 pending
        val claimed = s.claimDue(now = 2_000, limit = 1, staleBefore = 0)   // 1 delivering
        s.markDelivered(claimed[0].id, claimed[0].claimToken, 2_100)        // → delivered
        val c2 = s.claimDue(now = 3_000, limit = 1, staleBefore = 0)
        s.markFailed(c2[0].id, c2[0].claimToken, 3_100, null, "poison")     // → failed
        val c3 = s.claimDue(now = 4_000, limit = 1, staleBefore = 0)        // delivering, left in flight
        db.transaction { s.append(pending(9)) }                              // 1 pending, created_at=1000

        val st = ops(db).stats()
        assertEquals(1L, st.pending)
        assertEquals(1L, st.delivering)
        assertEquals(1L, st.delivered)
        assertEquals(1L, st.failed)
        assertEquals(0L, st.discarded)
        assertTrue((st.oldestPendingAgeMillis ?: -1) >= 0, "有待投递就应有年龄")
        assertTrue((st.oldestDeliveringAgeMillis ?: -1) >= 0, "有投递中就应有年龄")
    }

    @Test
    fun requeueOnlyFromTerminalFailure() = withMigratedDatabase { db ->
        val s = store(db)
        db.transaction { s.append(pending(1)); s.append(pending(2)) }
        val c = s.claimDue(now = 1_000, limit = 1, staleBefore = 0).single()
        s.markFailed(c.id, c.claimToken, 1_100, null, "poison")            // → 3
        assertEquals(3, db.statusOf(c.id))

        // 待投递的（第二条，status 0）不允许重投
        val other = db.fetchAll("SELECT id FROM infra_domain_events WHERE status = 0 AND id <> :id", mapOf("id" to c.id)).first().longOrNull("id")!!
        assertFailsWith<HttpException> { ops(db).requeue(other) }.let {
            assertEquals(NetonErrorCode.OPERATION_NOT_ALLOWED, it.code)
            assertEquals(InfraErrorCodes.DOMAIN_EVENT_NOT_REQUEUEABLE, it.message)
        }

        val r = ops(db).requeue(c.id)
        assertEquals(0, r.status)
        assertEquals(0, r.attempts, "重投要归零重试预算")
        assertEquals(null, r.claimToken, "旧令牌不能复活")
        // 重投后能被再次领取。ops 用墙钟写 next_attempt_at，所以领取也要用墙钟
        val wall = kotlin.time.Clock.System.now().toEpochMilliseconds()
        assertEquals(1, s.claimDue(now = wall, limit = 10, staleBefore = 0).count { it.id == c.id })
    }

    @Test
    fun discardFromPendingOrFailedButNotDelivering() = withMigratedDatabase { db ->
        val s = store(db)
        db.transaction { repeat(3) { s.append(pending(it)) } }
        val ids = db.fetchAll("SELECT id FROM infra_domain_events ORDER BY id").map { it.longOrNull("id")!! }

        // pending → discard OK
        val d1 = ops(db).discard(ids[0], "not needed")
        assertEquals(4, d1.status)
        assertEquals("not needed", d1.lastError)

        // failed → discard OK
        val c = s.claimDue(now = 1_000, limit = 1, staleBefore = 0).single()
        s.markFailed(c.id, c.claimToken, 1_100, null, "poison")
        assertEquals(4, ops(db).discard(c.id, null).status)

        // delivering → refused
        val c2 = s.claimDue(now = 2_000, limit = 1, staleBefore = 0).single()
        assertEquals(1, db.statusOf(c2.id))
        assertFailsWith<HttpException> { ops(db).discard(c2.id, "x") }.let {
            assertEquals(NetonErrorCode.OPERATION_NOT_ALLOWED, it.code)
            assertEquals(InfraErrorCodes.DOMAIN_EVENT_NOT_DISCARDABLE, it.message)
        }

        // 已丢弃的不会被领取
        assertTrue(s.claimDue(now = 100_000, limit = 10, staleBefore = 99_000).none { it.id == ids[0] })
    }

    @Test
    fun cleanupRemovesOnlyDeliveredAndDiscardedPastRetention() = withMigratedDatabase { db ->
        val s = store(db, clock = { 1_000L })
        db.transaction { repeat(4) { s.append(pending(it)) } }
        val ids = db.fetchAll("SELECT id FROM infra_domain_events ORDER BY id").map { it.longOrNull("id")!! }

        // ids[0] → delivered (updated_at 2000)
        val c0 = s.claimDue(now = 2_000, limit = 1, staleBefore = 0).single()
        s.markDelivered(c0.id, c0.claimToken, 2_000)
        // ids[1] → discarded via ops (updated_at = wall clock; 用 SQL 拨回去便于测试)
        ops(db).discard(ids[1], null)
        db.execute("UPDATE infra_domain_events SET updated_at = 2000 WHERE id = :id", mapOf("id" to ids[1]))
        // ids[2] → failed (must survive cleanup)
        val c2 = s.claimDue(now = 3_000, limit = 1, staleBefore = 0).single()
        s.markFailed(c2.id, c2.claimToken, 3_000, null, "poison")
        // ids[3] stays pending (must survive)

        // ops.now() 是墙钟；retention 设得很大，让 cutoff 落在 2000 之后但仍早于"现在"
        val nowWall = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val retention = nowWall - 2_500      // cutoff = 2500
        val deleted = ops(db).cleanup(retentionMillis = retention, batchSize = 1)

        assertEquals(2L, deleted, "只删已投递和已丢弃")
        val remaining = db.fetchAll("SELECT id, status FROM infra_domain_events ORDER BY id")
            .map { it.longOrNull("id")!! to it.intOrNull("status")!! }
        assertEquals(listOf(ids[2] to 3, ids[3] to 0), remaining)
    }
}
