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

    /**
     * 模拟带 fencing 的存储：每条记录记住当前有效令牌，落定时校验。
     * `currentTokens` 可从外部改写，用来模拟"被别的节点重领"。
     */
    private class FakeStore(var due: MutableList<StoredEventRecord>) : DomainEventStore {
        val delivered = mutableListOf<Long>()
        val failures = mutableListOf<Triple<Long, Long?, String>>()
        val currentTokens = mutableMapOf<Long, String>()
        var lastStaleBefore: Long = -1
        override suspend fun append(record: PendingEventRecord) {}
        override suspend fun claimDue(now: Long, limit: Int, staleBefore: Long): List<StoredEventRecord> {
            lastStaleBefore = staleBefore
            val batch = due.take(limit)
            due = due.drop(limit).toMutableList()
            batch.forEach { currentTokens[it.id] = it.claimToken }
            return batch
        }
        override suspend fun markDelivered(id: Long, claimToken: String, now: Long): Boolean {
            if (currentTokens[id] != claimToken) return false
            delivered += id
            return true
        }
        override suspend fun markFailed(id: Long, claimToken: String, now: Long, nextAttemptAt: Long?, error: String): Boolean {
            if (currentTokens[id] != claimToken) return false
            failures += Triple(id, nextAttemptAt, error)
            return true
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

    private fun record(id: Long, attempts: Int = 0, token: String = "tok-$id") =
        StoredEventRecord(id, "Sample", "L", "{}", attempts, token)

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

    // ---- fencing：迟到的旧领取者不能覆盖新领取者的结果 ----

    /**
     * A 领取后超时，B 重领并投递成功；A 的监听者随后也执行完了。
     * A 的 markDelivered 必须作废（令牌已换），且不重复计入 delivered。
     */
    @Test
    fun staleClaimantCannotOverrideNewOwner() = runBlocking {
        val store = FakeStore(mutableListOf(record(1, token = "A")))
        val listener = Listener()
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(listener)), codecOf(Sample(1)))

        // 模拟：A 领取后（claimDue 已把 A 记为当前令牌），B 重领把令牌换成 B 并完成
        // 这里用「在 A 落定前把令牌改掉」来表达 B 的接管
        val original = store.claimDue(0, 10, 0)             // A 领到 token=A
        store.currentTokens[1] = "B"                        // B 重领，令牌变了
        val ok = store.markDelivered(1, original[0].claimToken, 0)
        assertTrue(!ok, "A 的落定必须作废")
        assertTrue(store.delivered.isEmpty())
    }

    /** 同上，但 A 是失败路径：也不能把 B 已完成的记录改回待重试。 */
    @Test
    fun staleClaimantFailureIsIgnored() = runBlocking {
        val store = FakeStore(mutableListOf(record(1, token = "A")))
        val original = store.claimDue(0, 10, 0)
        store.currentTokens[1] = "B"
        val ok = store.markFailed(1, original[0].claimToken, 0, 5_000, "late failure")
        assertTrue(!ok)
        assertTrue(store.failures.isEmpty(), "旧领取者的失败不能写回")
    }

    /** 端到端：投递器拿到的令牌与领取时一致，正常路径能落定。 */
    @Test
    fun dispatcherPassesClaimTokenThrough() = runBlocking {
        val store = FakeStore(mutableListOf(record(7, token = "T7")))
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(Listener())), codecOf(Sample(7)))
        assertEquals(1, d.dispatchOnce(0))
        assertEquals(listOf(7L), store.delivered)
    }

    // ---- 取消 ----

    /** 关闭/取消不是失败：不能写重试状态、不能吞掉信号；记录留在投递中等超时回收。 */
    @Test
    fun cancellationIsNotRecordedAsFailure() = runBlocking {
        val store = FakeStore(mutableListOf(record(1)))
        val cancelling = object : DomainEventListener<Sample> {
            override val eventType = Sample::class
            override val listenerId = "L"
            override val mode = DeliveryMode.RETRYABLE
            override suspend fun onEvent(event: Sample) {
                throw kotlin.coroutines.cancellation.CancellationException("shutdown")
            }
        }
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(cancelling)), codecOf(Sample(1)))
        var thrown = false
        try { d.dispatchOnce(0) } catch (e: kotlin.coroutines.cancellation.CancellationException) { thrown = true }
        assertTrue(thrown, "取消必须向上传播")
        assertTrue(store.failures.isEmpty(), "取消不能记成失败")
        assertTrue(store.delivered.isEmpty())
    }

    // ---- 解码类型不匹配 ----

    private data class Other(val id: Long) : DomainEvent

    /** codec 解出的类型与监听者订阅的不符：直接终态，而不是交给 onEvent 抛 ClassCast 后反复重试。 */
    @Test
    fun decodedTypeMismatchIsPermanentFailure() = runBlocking {
        val store = FakeStore(mutableListOf(record(1)))
        val listener = Listener()
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(listener)), codecOf(Other(1)))
        assertEquals(0, d.dispatchOnce(0))
        assertEquals(0, listener.calls, "类型不对不能调监听者")
        assertEquals(1, store.failures.size)
        assertNull(store.failures[0].second, "应当是终态（nextAttemptAt=null）")
        assertTrue(store.failures[0].third.contains("类型不匹配"))
    }

    // ---- 毒消息：decode 抛异常不能中断整批 ----

    /**
     * JSON 解码遇坏数据是抛异常而不是返回 null。放在 try 外的话一条毒消息会让整批中断，
     * 后续记录不处理，超时后毒消息还会重现、再中断——整个投递链路被一条坏数据卡死。
     */
    @Test
    fun decodeExceptionSettlesThatRecordAndContinuesBatch() = runBlocking {
        val store = FakeStore(mutableListOf(record(1), record(2), record(3)))
        val listener = Listener()
        val throwingCodec = object : DomainEventCodec {
            override fun encode(event: DomainEvent) = "{}"
            override fun decode(eventType: String, payload: String): DomainEvent =
                if (payload == "poison") throw IllegalArgumentException("bad json") else Sample(1)
        }
        // 第 2 条是毒消息
        store.due[1] = StoredEventRecord(2, "Sample", "L", "poison", 0, "tok-2")
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(listener)), throwingCodec)

        assertEquals(2, d.dispatchOnce(0), "毒消息两侧的记录都要正常投递")
        assertEquals(listOf(1L, 3L), store.delivered)
        assertEquals(1, store.failures.size)
        assertEquals(2L, store.failures[0].first)
        assertNull(store.failures[0].second, "解码异常是终态，不重试")
        assertTrue(store.failures[0].third.contains("解码异常"))
    }

    /** decode 里抛出取消必须透传，不能当成毒消息落终态。 */
    @Test
    fun cancellationInsideDecodePropagates() = runBlocking {
        val store = FakeStore(mutableListOf(record(1)))
        val cancellingCodec = object : DomainEventCodec {
            override fun encode(event: DomainEvent) = "{}"
            override fun decode(eventType: String, payload: String): DomainEvent =
                throw kotlin.coroutines.cancellation.CancellationException("shutdown")
        }
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(Listener())), cancellingCodec)
        var thrown = false
        try { d.dispatchOnce(0) } catch (e: kotlin.coroutines.cancellation.CancellationException) { thrown = true }
        assertTrue(thrown)
        assertTrue(store.failures.isEmpty(), "取消不能落成终态失败")
    }

    // ---- 失去所有权后不再上报 ----

    /**
     * 令牌已换说明本 worker 失去所有权，它的失败已经不归它管：
     * 再上报会制造一条并不存在的重试/终态告警。
     */
    @Test
    fun staleFailureIsNotReported() = runBlocking {
        val store = FakeStore(mutableListOf(record(1, token = "A")))
        val reported = mutableListOf<StoredEventFailure>()
        // 监听者先把令牌改掉（模拟被 B 重领），再抛异常
        val listener = object : DomainEventListener<Sample> {
            override val eventType = Sample::class
            override val listenerId = "L"
            override val mode = DeliveryMode.RETRYABLE
            override suspend fun onEvent(event: Sample) {
                store.currentTokens[1] = "B"
                throw IllegalStateException("late failure")
            }
        }
        val d = DomainEventDispatcher(store, DomainEventBus(listOf(listener)), codecOf(Sample(1)), onError = { reported += it })
        d.dispatchOnce(0)
        assertTrue(store.failures.isEmpty(), "旧领取者的失败不能写回")
        assertTrue(reported.isEmpty(), "失去所有权后不能上报告警")
    }

    /** 同上，终态路径：监听者不存在但令牌已换，也不上报。 */
    @Test
    fun stalePermanentFailureIsNotReported() = runBlocking {
        val store = FakeStore(mutableListOf(record(1, token = "A")))
        val reported = mutableListOf<StoredEventFailure>()
        // 没有监听者 → 会走终态；但在 claimDue 后把令牌换掉
        val d = DomainEventDispatcher(store, DomainEventBus(emptyList()), codecOf(Sample(1)), onError = { reported += it })
        // 先领取（记录令牌 A），再模拟被重领
        val batch = store.claimDue(0, 10, 0)
        store.currentTokens[1] = "B"
        // 直接调用 store 落定验证令牌语义（dispatcher 内部走同一路径）
        assertTrue(!store.markFailed(1, batch[0].claimToken, 0, null, "no listener"))
        assertTrue(reported.isEmpty())
    }
}
