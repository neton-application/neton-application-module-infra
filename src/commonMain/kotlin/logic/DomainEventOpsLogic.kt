package logic

import port.InfraErrorCodes
import dto.PageResponse
import kotlinx.serialization.Serializable
import model.DomainEventRecord
import neton.core.http.NotFoundException
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.database.api.DbContext
import neton.database.dsl.desc
import neton.database.dsl.eq
import neton.database.dsl.like
import neton.logging.Logger
import table.DomainEventRecordTable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * outbox 的运维面：查、看、统计、手工重投、手工丢弃、过期清理。
 *
 * 投递本身不在这里（见 `event.DomainEventDispatcher`）。这里补的是「失败了有人看得见、
 * 看见了有办法处置」——终态失败的记录规范说"等待人工介入"，没有这一层人根本介入不了。
 */
@OptIn(ExperimentalTime::class)
@neton.core.annotations.Logic(logger = "logic.domain-event-ops")
class DomainEventOpsLogic(
    private val log: Logger,
    private val db: DbContext,
) {
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()


    suspend fun page(
        page: Int,
        size: Int,
        status: Int? = null,
        listenerId: String? = null,
        eventType: String? = null,
    ): PageResponse<DomainEventRecord> {
        val result = DomainEventRecordTable.query {
            where {
                and(
                    whenPresent(status) { DomainEventRecord::status eq it },
                    whenNotBlank(listenerId) { DomainEventRecord::listenerId eq it },
                    whenNotBlank(eventType) { DomainEventRecord::eventType like "%$it%" },
                )
            }
            orderBy(DomainEventRecord::id.desc())
        }.page(page, size)
        return PageResponse(
            result.items, result.total, page, size,
            if (size > 0) ((result.total + size - 1) / size).toInt() else 0,
        )
    }

    suspend fun get(id: Long): DomainEventRecord =
        DomainEventRecordTable.get(id) ?: throw NotFoundException(InfraErrorCodes.DOMAIN_EVENT_NOT_FOUND)

    /**
     * 积压指标。这些数字是 outbox 唯一的健康信号——投递器挂了、下游一直失败、
     * 记录滞留，都只在这里显形。
     */
    suspend fun stats(): DomainEventStats {
        val t = now()
        val row = db.fetchAll(
            """
            SELECT
              COUNT(*) FILTER (WHERE status = 0)                          AS pending,
              COUNT(*) FILTER (WHERE status = 1)                          AS delivering,
              COUNT(*) FILTER (WHERE status = 2)                          AS delivered,
              COUNT(*) FILTER (WHERE status = 3)                          AS failed,
              COUNT(*) FILTER (WHERE status = 4)                          AS discarded,
              MIN(created_at) FILTER (WHERE status = 0)                   AS oldest_pending_at,
              MIN(updated_at) FILTER (WHERE status = 1)                   AS oldest_delivering_at
            FROM infra_domain_events
            """.trimIndent(),
        ).first()
        val oldestPending = row.longOrNull("oldest_pending_at")
        val oldestDelivering = row.longOrNull("oldest_delivering_at")
        return DomainEventStats(
            pending = row.longOrNull("pending") ?: 0,
            delivering = row.longOrNull("delivering") ?: 0,
            delivered = row.longOrNull("delivered") ?: 0,
            failed = row.longOrNull("failed") ?: 0,
            discarded = row.longOrNull("discarded") ?: 0,
            oldestPendingAgeMillis = oldestPending?.let { t - it },
            oldestDeliveringAgeMillis = oldestDelivering?.let { t - it },
        )
    }

    /**
     * 把终态失败的记录放回待投递。
     *
     * 只允许从终态失败（3）出发：待投递/投递中的记录本来就在流转，重投它们只会制造重复；
     * 已投递/已丢弃的记录重投是业务决定，不该藏在一个"重投"按钮后面。
     * `attempts` 归零，让它重新享受完整的重试预算；`claim_token` 清掉，旧领取者的令牌不能复活。
     */
    suspend fun requeue(id: Long): DomainEventRecord {
        val t = now()
        val affected = db.execute(
            """
            UPDATE infra_domain_events
            SET status = 0, attempts = 0, next_attempt_at = :now, claim_token = NULL,
                last_error = NULL, updated_at = :now
            WHERE id = :id AND status = 3
            """.trimIndent(),
            mapOf("id" to id, "now" to t),
        )
        if (affected == 0L) {
            get(id) // 不存在 → DOMAIN_EVENT_NOT_FOUND
            throw HttpException(NetonErrorCode.OPERATION_NOT_ALLOWED, InfraErrorCodes.DOMAIN_EVENT_NOT_REQUEUEABLE)
        }
        log.info("event.ops.requeue", mapOf("id" to id))
        return get(id)
    }

    /**
     * 人工丢弃：不再投递，但保留行以便审计。
     *
     * 允许从待投递或终态失败出发。投递中的不允许——它可能正在被某个节点执行，
     * 丢弃与执行并发会让状态不可解释；等它落定（成功、失败或超时回收）再说。
     */
    suspend fun discard(id: Long, reason: String?): DomainEventRecord {
        val t = now()
        val affected = db.execute(
            """
            UPDATE infra_domain_events
            SET status = 4, last_error = :reason, updated_at = :now
            WHERE id = :id AND status IN (0, 3)
            """.trimIndent(),
            mapOf("id" to id, "reason" to (reason?.take(500) ?: "discarded manually"), "now" to t),
        )
        if (affected == 0L) {
            get(id) // 不存在 → DOMAIN_EVENT_NOT_FOUND
            throw HttpException(NetonErrorCode.OPERATION_NOT_ALLOWED, InfraErrorCodes.DOMAIN_EVENT_NOT_DISCARDABLE)
        }
        log.info("event.ops.discard", mapOf("id" to id, "reason" to reason))
        return get(id)
    }

    /**
     * 清理已投递 / 已丢弃且超过保留期的记录，分批删避免长事务锁表。
     *
     * @return 删除的行数
     */
    suspend fun cleanup(retentionMillis: Long, batchSize: Int = 1_000): Long {
        val cutoff = now() - retentionMillis
        var total = 0L
        while (true) {
            val deleted = db.execute(
                """
                DELETE FROM infra_domain_events
                WHERE id IN (
                    SELECT id FROM infra_domain_events
                    WHERE status IN (2, 4) AND updated_at < :cutoff
                    ORDER BY id LIMIT :limit
                )
                """.trimIndent(),
                mapOf("cutoff" to cutoff, "limit" to batchSize),
            )
            total += deleted
            if (deleted < batchSize) break
        }
        if (total > 0) log.info("event.ops.cleanup", mapOf("deleted" to total, "cutoff" to cutoff))
        return total
    }
}

@Serializable
data class DomainEventStats(
    val pending: Long,
    val delivering: Long,
    val delivered: Long,
    val failed: Long,
    val discarded: Long,
    /** 最老待投递记录的年龄；null 表示没有待投递 */
    val oldestPendingAgeMillis: Long?,
    /** 最老投递中记录的年龄；持续增长说明有节点领了不落定 */
    val oldestDeliveringAgeMillis: Long?,
)
