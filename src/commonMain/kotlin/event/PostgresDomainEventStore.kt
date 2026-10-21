package event

import neton.core.event.DomainEventStore
import neton.core.event.PendingEventRecord
import neton.core.event.StoredEventRecord
import neton.database.api.DbContext
import neton.security.random.SecureRandom
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 待投递事件的 PostgreSQL 实现（框架端口 `DomainEventStore` 的落地）。
 *
 * 数据库是这里唯一可信的载体：投递记录与发布方的业务写入落在**同一个本地事务**里，
 * 发布方回滚记录随之消失，发布方提交记录必然存在。任何进程外的载体都做不到这一点 ——
 * 先写库再投递会留下丢失窗口，先投递再写库则可能投出并未真正发生的事件。
 *
 * **只支持 PostgreSQL**：领取用 `FOR UPDATE SKIP LOCKED` + `UPDATE … RETURNING`，
 * 都是 PG 语法；迁移脚本也只有 `sql/postgresql/`。构造时校验方言，其它数据库直接失败，
 * 而不是跑到第一次领取时才报一个 SQL 语法错误。
 *
 * 表由 infra 自己的迁移建立（V008 / V009 / V010），与本模块其它表一致。
 */
@OptIn(ExperimentalTime::class)
class PostgresDomainEventStore(
    private val db: DbContext,
    private val config: DomainEventStoreConfig = DomainEventStoreConfig(),
    /** 取当前时刻。可注入以便测试，生产用系统时钟。 */
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** 写入一条记录之后调用（仍在发布方事务内）：唤醒本节点的即时投递（[PromptEventDispatch]）。 */
    private val onAppend: () -> Unit = {},
) : DomainEventStore {

    private val table = config.table

    init {
        // 表名直接拼进 SQL，必须是合法标识符——否则不是注入就是语法错误，
        // 而且非默认表名不会由 V008–V010 迁移建立，需要使用方自己建表并保持结构一致。
        require(IDENTIFIER.matches(table)) {
            "DomainEventStoreConfig.table='$table' 不是合法的 SQL 标识符（只允许字母、数字、下划线，" +
                "且不能以数字开头）。"
        }
        require(db.dialect.name == "postgres") {
            "PostgresDomainEventStore 只支持 PostgreSQL，当前方言是 '${db.dialect.name}'。" +
                "领取语句依赖 FOR UPDATE SKIP LOCKED 与 UPDATE … RETURNING。"
        }
    }

    /**
     * 在**调用方当前事务内**写入一条待投递记录。
     *
     * 断言处在事务里，而不是把这条要求留给注释：事务外写入会立即提交，随后业务失败也
     * 收不回来，等于投出了一个并未发生的事件——恰好是选事务性 outbox 要消除的问题。
     * 这类错误在单元测试里几乎不会暴露，只有生产上某次业务写入失败时才显形，所以必须 fail-fast。
     */
    override suspend fun append(record: PendingEventRecord) {
        check(db.inTransaction()) {
            "RETRYABLE 事件 ${record.eventType} → ${record.listenerId} 必须在数据库事务内发布：" +
                "outbox 记录要与业务写入同事务提交或回滚。请把 publish 放进 db.transaction { } 里。"
        }
        // 时间在这里取：总线不持有时钟，之前由它传入的 createdAt 恒为 0，
        // 落库的每一行时间戳都是假的，而滞留回收要靠 updated_at 判断。
        val ts = now()
        db.execute(
            """
            INSERT INTO $table (event_type, listener_id, payload, status, attempts, next_attempt_at, created_at, updated_at)
            VALUES (:type, :listener, :payload, $STATUS_PENDING, 0, :ts, :ts, :ts)
            """.trimIndent(),
            mapOf(
                "type" to record.eventType,
                "listener" to record.listenerId,
                "payload" to record.payload,
                "ts" to ts,
            ),
        )
        onAppend()
    }

    /**
     * 领取一批记录并占位。
     *
     * 两类记录都要领：
     * 1. 到期待投递的（status=0 且 next_attempt_at 已到）
     * 2. **滞留在"投递中"的**（status=1 且超过可见性超时仍未落定）——
     *    领取之后、markDelivered/markFailed 之前进程崩溃，记录就卡在 status=1。
     *    没有第 2 类，这条记录永远不会被再次领取、也不会报错，事件静默永久丢失，
     *    而"不丢"正是整个事务性 outbox 存在的理由。
     *
     * 每次领取生成新的 `claim_token`。**这是 fencing**：行锁只覆盖这一条语句，监听者
     * 执行期间早已释放。领取者 A 超时后被 B 重领，A 迟到的 markDelivered/markFailed 若不带
     * 令牌，会把 B 已完成的记录改回待重试或终态失败。令牌让 A 的落定自动失效。
     *
     * 用 `FOR UPDATE SKIP LOCKED` 单语句领取：先查后逐行条件更新是 N+1（一批 100 条
     * 就是 101 次往返），且多实例下大量更新会落空。SKIP LOCKED 让并发的领取者各自
     * 跳过对方已锁定的行，一次往返拿到互不重叠的一批。
     */
    override suspend fun claimDue(now: Long, limit: Int, staleBefore: Long): List<StoredEventRecord> {
        val token = SecureRandom.hex(16)
        val rows = db.fetchAll(
            """
            UPDATE $table SET status = $STATUS_DELIVERING, claim_token = :token, updated_at = :now
            WHERE id IN (
                SELECT id FROM $table
                WHERE (status = $STATUS_PENDING AND next_attempt_at <= :now)
                   OR (status = $STATUS_DELIVERING AND updated_at <= :stale)
                ORDER BY id ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, event_type, listener_id, payload, attempts
            """.trimIndent(),
            mapOf("token" to token, "now" to now, "stale" to staleBefore, "limit" to limit),
        )

        return rows.mapNotNull { row ->
            val id = row.longOrNull("id") ?: return@mapNotNull null
            StoredEventRecord(
                id = id,
                eventType = row.stringOrNull("event_type").orEmpty(),
                listenerId = row.stringOrNull("listener_id").orEmpty(),
                payload = row.stringOrNull("payload").orEmpty(),
                attempts = row.intOrNull("attempts") ?: 0,
                claimToken = token,
            )
        }
    }

    /**
     * 令牌不匹配时更新 0 行、返回 false：说明这条记录已被别的领取者接管，
     * 本次落定作废。**不抛错**——迟到的旧领取者本来就该安静退出。
     */
    override suspend fun markDelivered(id: Long, claimToken: String, now: Long): Boolean {
        val affected = db.execute(
            """
            UPDATE $table SET status = $STATUS_DELIVERED, updated_at = :now
            WHERE id = :id AND status = $STATUS_DELIVERING AND claim_token = :token
            """.trimIndent(),
            mapOf("id" to id, "token" to claimToken, "now" to now),
        )
        return affected > 0
    }

    /**
     * [nextAttemptAt] 为 null 表示不再重试，转终态失败等待人工介入 ——
     * 无限重试会让一条坏记录永远占着投递批次，把后面的事件一起拖住。
     */
    override suspend fun markFailed(
        id: Long,
        claimToken: String,
        now: Long,
        nextAttemptAt: Long?,
        error: String,
    ): Boolean {
        val status = if (nextAttemptAt == null) STATUS_FAILED else STATUS_PENDING
        val affected = db.execute(
            """
            UPDATE $table
            SET status = $status, attempts = attempts + 1, next_attempt_at = :next,
                last_error = :err, updated_at = :now
            WHERE id = :id AND status = $STATUS_DELIVERING AND claim_token = :token
            """.trimIndent(),
            mapOf(
                "id" to id,
                "token" to claimToken,
                "next" to (nextAttemptAt ?: now),
                "err" to error.take(config.maxErrorLength),
                "now" to now,
            ),
        )
        return affected > 0
    }

    companion object {
        private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")

        const val STATUS_PENDING = 0
        const val STATUS_DELIVERING = 1
        const val STATUS_DELIVERED = 2
        const val STATUS_FAILED = 3
    }
}

/**
 * 事件表配置。
 *
 * 表名可配是为了让同库多部署（如测试与正式共用一个数据库实例）互不干扰。
 * **非默认表名不会由迁移脚本建立**：V008–V010 只创建 `infra_domain_events`，
 * 自定义表需由使用方按同样结构自行建表。
 */
data class DomainEventStoreConfig(
    val table: String = "infra_domain_events",
    /** 单条记录保留的错误文本长度，超出截断，避免异常堆栈把行撑爆。 */
    val maxErrorLength: Int = 500,
)
