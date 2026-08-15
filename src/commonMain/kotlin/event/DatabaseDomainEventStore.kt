package event

import neton.core.event.DomainEventStore
import neton.core.event.PendingEventRecord
import neton.core.event.StoredEventRecord
import neton.database.api.DbContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 待投递事件的数据库实现（框架端口 `DomainEventStore` 的落地）。
 *
 * 数据库是这里唯一可信的载体：投递记录与发布方的业务写入落在**同一个本地事务**里，
 * 发布方回滚记录随之消失，发布方提交记录必然存在。任何进程外的载体都做不到这一点 ——
 * 先写库再投递会留下丢失窗口，先投递再写库则可能投出并未真正发生的事件。
 *
 * 载体的选择属于应用层：框架只定义端口，用数据库、线程还是中间件由使用方决定，
 * 框架因此保持轻量。表由 infra 自己的迁移建立（V008），与本模块其它表一致。
 */
@OptIn(ExperimentalTime::class)
class DatabaseDomainEventStore(
    private val db: DbContext,
    private val config: DomainEventStoreConfig = DomainEventStoreConfig(),
    /** 取当前时刻。可注入以便测试，生产用系统时钟。 */
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : DomainEventStore {

    private val table = config.table

    override suspend fun append(record: PendingEventRecord) {
        // 时间在这里取：总线不持有时钟，之前由它传入的 createdAt 恒为 0，
        // 落库的每一行时间戳都是假的，而下面的滞留回收要靠 updated_at 判断。
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
     * 用 `FOR UPDATE SKIP LOCKED` 单语句领取：先查后逐行条件更新是 N+1（一批 100 条
     * 就是 101 次往返），且多实例下大量更新会落空。SKIP LOCKED 让并发的领取者各自
     * 跳过对方已锁定的行，一次往返拿到互不重叠的一批。
     */
    override suspend fun claimDue(now: Long, limit: Int, staleBefore: Long): List<StoredEventRecord> {
        val rows = db.fetchAll(
            """
            UPDATE $table SET status = $STATUS_DELIVERING, updated_at = :now
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
            mapOf("now" to now, "stale" to staleBefore, "limit" to limit),
        )

        return rows.mapNotNull { row ->
            val id = row.longOrNull("id") ?: return@mapNotNull null
            StoredEventRecord(
                id = id,
                eventType = row.stringOrNull("event_type").orEmpty(),
                listenerId = row.stringOrNull("listener_id").orEmpty(),
                payload = row.stringOrNull("payload").orEmpty(),
                attempts = row.intOrNull("attempts") ?: 0,
            )
        }
    }

    override suspend fun markDelivered(id: Long, now: Long) {
        db.execute(
            "UPDATE $table SET status = $STATUS_DELIVERED, updated_at = :now WHERE id = :id",
            mapOf("id" to id, "now" to now),
        )
    }

    /**
     * [nextAttemptAt] 为 null 表示不再重试，转终态失败等待人工介入 ——
     * 无限重试会让一条坏记录永远占着投递批次，把后面的事件一起拖住。
     */
    override suspend fun markFailed(id: Long, now: Long, nextAttemptAt: Long?, error: String) {
        val status = if (nextAttemptAt == null) STATUS_FAILED else STATUS_PENDING
        db.execute(
            """
            UPDATE $table
            SET status = $status, attempts = attempts + 1, next_attempt_at = :next,
                last_error = :err, updated_at = :now
            WHERE id = :id
            """.trimIndent(),
            mapOf(
                "id" to id,
                "next" to (nextAttemptAt ?: now),
                "err" to error.take(config.maxErrorLength),
                "now" to now,
            ),
        )
    }

    companion object {
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
 */
data class DomainEventStoreConfig(
    val table: String = "infra_domain_events",
    /** 单条记录保留的错误文本长度，超出截断，避免异常堆栈把行撑爆。 */
    val maxErrorLength: Int = 500,
)
