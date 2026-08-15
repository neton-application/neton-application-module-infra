package event

import neton.core.config.ConfigTypeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainEventSettingsTest {

    @Test
    fun defaultsWhenSectionMissing() {
        val s = DomainEventSettings.fromModuleConfig(mapOf("other" to mapOf("x" to 1)))
        assertEquals(DispatchConfig(), s.dispatch)
        assertEquals(7, s.retentionDays)
    }

    @Test
    fun defaultsWhenConfigNull() {
        assertEquals(DomainEventSettings(), DomainEventSettings.fromModuleConfig(null))
    }

    @Test
    fun readsEveryKey() {
        val s = DomainEventSettings.fromModuleConfig(
            mapOf(
                "domain_events" to mapOf(
                    "batch_size" to 50,
                    "max_attempts" to 3,
                    "base_backoff_ms" to 1000,
                    "max_backoff_ms" to 60000,
                    "visibility_timeout_ms" to 120000,
                    "retention_days" to 30,
                    "backlog_warn_pending" to 200,
                    "backlog_warn_age_ms" to 30000,
                ),
            ),
        )
        assertEquals(50, s.dispatch.batchSize)
        assertEquals(3, s.dispatch.maxAttempts)
        assertEquals(1_000L, s.dispatch.baseBackoffMillis)
        assertEquals(60_000L, s.dispatch.maxBackoffMillis)
        assertEquals(120_000L, s.dispatch.visibilityTimeoutMillis)
        assertEquals(30, s.retentionDays)
        assertEquals(200L, s.backlogWarnPending)
        assertEquals(30_000L, s.backlogWarnAgeMillis)
    }

    @Test
    fun partialSectionKeepsOtherDefaults() {
        val s = DomainEventSettings.fromModuleConfig(mapOf("domain_events" to mapOf("retention_days" to 1)))
        assertEquals(1, s.retentionDays)
        assertEquals(DispatchConfig(), s.dispatch)
    }

    /** 类型错误直接抛，不回退默认值——静默回退正是这轮反复清理的问题。 */
    @Test
    fun rejectsNonNumericValue() {
        assertFailsWith<ConfigTypeException> {
            DomainEventSettings.fromModuleConfig(mapOf("domain_events" to mapOf("batch_size" to "many")))
        }
    }
}
