package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Id
import neton.database.annotations.Table

/**
 * outbox 表的只读视图，供运维查询与手工处置。
 *
 * 投递路径不用它——`PostgresDomainEventStore` 用手写 SQL，因为领取需要
 * `FOR UPDATE SKIP LOCKED` 与 `UPDATE … RETURNING`。这里只做分页、统计、重投、丢弃。
 */
@Serializable
@Table("infra_domain_events")
data class DomainEventRecord(
    @Id
    val id: Long = 0,
    val eventType: String,
    val listenerId: String,
    val payload: String,
    /** 0 待投递 · 1 投递中 · 2 已投递 · 3 终态失败 · 4 已丢弃（人工） */
    val status: Int = 0,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val claimToken: String? = null,
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_DELIVERING = 1
        const val STATUS_DELIVERED = 2
        const val STATUS_FAILED = 3
        /** 人工丢弃：不再投递，保留行以便审计，随已投递记录一起被清理任务回收 */
        const val STATUS_DISCARDED = 4
    }
}
