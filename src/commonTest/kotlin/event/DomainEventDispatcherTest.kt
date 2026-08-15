package event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import neton.core.event.DeliveryMode
import neton.core.event.DomainEvent
import neton.core.event.DomainEventBus
import neton.core.event.DomainEventListener
import neton.core.event.DomainEventCodec
import neton.core.event.DomainEventStore
import neton.core.event.PendingEventRecord
import neton.core.event.StoredEventRecord

/**
 * 投递语义。这些断言守的是"事件不会丢、也不会永远卡住"：
 * 重试要能自愈，坏记录要能退场，两者都错了积压会拖垮整个投递链路。
 */
class DomainEventDispatcherTest {

    private data class Sample(val id: Long) : DomainEvent

    private class FakeStore(var due: MutableList<StoredEventRecord>) : DomainEventStore {
        val delivered = mutableListOf<Long>()
        val failures = mutableListOf<Triple<Long, Long?, String>>()
        override suspend fun append(record: PendingEventRecord) {}
        var lastStaleBefore: Long = -1
        override suspend fun claimDue(now: Long, limit: Int, staleBefore: Long): List<StoredEventRecord> {
            lastStaleBefore = staleBefore
            val batch = due.take(limit)
            due = due.drop(limit).toMutableList()
            return batch
        }
        override suspend fun markDelivered(id: Long, now: Long) { delivered += id }
        override suspend fun markFailed(id: Long, now: Long, nextAttemptAt: Long?, error: String) {
            failures += Triple(id, nextAttemptAt, error)
        }
    }

    private class Listener(
        val fail: Boolean = false,
        override val listenerId: String = "L",
    ) : DomainEventListener<Sample> {
        override val eventType = Sample::class
        override val mode = DeliveryMode.RETRYABLE
        var calls = 0
        override suspend fun onEvent(event: Sample) {
            calls++
            if (fail) throw IllegalStateException("投递失败")
        }
    }

    /** 固定还原成指定事件（或还原失败），只为驱动投递分支。 */
    private fun codecOf(event: DomainEvent?) = object : DomainEventCodec {
        override fun encode(event: DomainEvent) = "{}"
        override fun decode(eventType: String, payload: String) = event
    }

    private fun record(id: Long, attempts: Int = 0) =
        StoredEventRecord(id, "Sample", "L", "{}", attempts)

    @Test
    fun deliversAndMarksDone() = runBlocking {
        val store = FakeStore(mutableListOf(record(1)))
        val listener = Listener()
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(listener)), codecOf(Sample(1)))
        assertEquals(1, d.dispatchOnce(1_000))
        assertEquals(1, listener.calls)
        assertEquals(listOf(1L), store.delivered)
    }

    /** 失败要安排下一次重试，而不是丢弃。 */
    @Test
    fun failureSchedulesRetry() = runBlocking {
        val store = FakeStore(mutableListOf(record(2)))
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(Listener(fail = true))), codecOf(Sample(2)))
        d.dispatchOnce(1_000)
        val (id, next, reason) = store.failures.single()
        assertEquals(2L, id)
        assertTrue(next != null && next > 1_000, "应安排未来的重试时间")
        assertEquals("投递失败", reason)
    }

    /** 超过上限转终态：一条坏记录不能永远占着投递批次。 */
    @Test
    fun exhaustedAttemptsBecomePermanent() = runBlocking {
        val store = FakeStore(mutableListOf(record(3, attempts = 7)))
        val cfg = DispatchConfig(maxAttempts = 8)
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(Listener(fail = true))), codecOf(Sample(3)), cfg)
        d.dispatchOnce(1_000)
        assertNull(store.failures.single().second, "达到上限应不再重试")
    }

    /** 监听者已下线：重试无意义，直接转终态留待人工处理。 */
    @Test
    fun missingListenerIsPermanentFailure() = runBlocking {
        val store = FakeStore(mutableListOf(record(4)))
        val d = DomainEventDispatcher(store, DomainEventBus(emptyList()), codecOf(Sample(4)))
        d.dispatchOnce(1_000)
        val f = store.failures.single()
        assertNull(f.second)
        assertTrue(f.third.contains("监听者不存在"))
    }

    /** 载荷还原不出来同样是终态，不该反复重试。 */
    @Test
    fun undeserializablePayloadIsPermanentFailure() = runBlocking {
        val store = FakeStore(mutableListOf(record(5)))
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(Listener())), codecOf(null))
        d.dispatchOnce(1_000)
        assertNull(store.failures.single().second)
    }

    /** 退避要封顶，否则尾部重试会被推到几天后，等于不再投递。 */
    @Test
    fun backoffIsCapped() = runBlocking {
        val store = FakeStore(mutableListOf(record(6, attempts = 6)))
        val cfg = DispatchConfig(maxAttempts = 100, baseBackoffMillis = 5_000, maxBackoffMillis = 60_000)
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(Listener(fail = true))), codecOf(Sample(6)), cfg)
        d.dispatchOnce(0)
        assertEquals(60_000L, store.failures.single().second)
    }

    /**
     * 领取时必须带上可见性超时窗口，否则崩溃后滞留在"投递中"的记录永远捞不回来——
     * 那是静默丢失，不是至少一次。
     */
    @Test
    fun claimPassesVisibilityWindow() = runBlocking {
        val store = FakeStore(mutableListOf())
        val cfg = DispatchConfig(visibilityTimeoutMillis = 60_000)
        DomainEventDispatcher(store, DomainEventBus(), codecOf(null), cfg).dispatchOnce(1_000_000)
        assertEquals(1_000_000 - 60_000, store.lastStaleBefore)
    }
}
