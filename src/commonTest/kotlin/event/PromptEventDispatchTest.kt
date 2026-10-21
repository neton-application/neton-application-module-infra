package event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class PromptEventDispatchTest {

    private fun <T> withDispatch(delays: List<Long>, dispatch: suspend () -> Int, onError: (Throwable) -> Unit = {}, block: suspend (PromptEventDispatch) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prompt = PromptEventDispatch(dispatch, delays, onError)
        prompt.start(scope)
        try {
            block(prompt)
        } finally {
            prompt.stop()
            scope.cancel()
        }
    }

    @Test
    fun aSignalRunsOneRoundPerSettleDelayThenStops() {
        val rounds = AtomicInt(0)
        withDispatch(listOf(10, 20, 30), { rounds.incrementAndFetch(); 0 }) { prompt ->
            prompt.signal()
            delay(250)
            assertEquals(3, rounds.load())
            delay(100)
            assertEquals(3, rounds.load(), "no rounds without a new signal")
        }
    }

    @Test
    fun nothingRunsBeforeTheFirstSignal() {
        val rounds = AtomicInt(0)
        withDispatch(listOf(5), { rounds.incrementAndFetch(); 0 }) {
            delay(80)
            assertEquals(0, rounds.load())
        }
    }

    @Test
    fun continuousSignalsKeepDispatchingInsteadOfPostponingForever() {
        val rounds = AtomicInt(0)
        withDispatch(listOf(20, 1_000), { rounds.incrementAndFetch(); 0 }) { prompt ->
            repeat(30) {
                prompt.signal()
                delay(10)
            }
            assertTrue(rounds.load() >= 5, "dispatch must keep running under steady writes, ran ${rounds.load()} rounds")
        }
    }

    @Test
    fun aFailingRoundIsReportedAndTheNextRoundStillRuns() {
        val rounds = AtomicInt(0)
        val errors = AtomicInt(0)
        withDispatch(listOf(5, 5), { if (rounds.incrementAndFetch() == 1) error("boom") else 1 }, onError = { errors.incrementAndFetch() }) { prompt ->
            prompt.signal()
            delay(150)
            assertEquals(2, rounds.load())
            assertEquals(1, errors.load())
        }
    }

    @Test
    fun settleDelaysAreValidated() {
        assertFailsWith<IllegalArgumentException> { PromptEventDispatch({ 0 }, emptyList()) }
        assertFailsWith<IllegalArgumentException> { PromptEventDispatch({ 0 }, listOf(-1)) }
    }
}
