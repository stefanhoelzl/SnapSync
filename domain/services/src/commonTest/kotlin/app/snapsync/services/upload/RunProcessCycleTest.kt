package app.snapsync.services.upload

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import app.snapsync.model.CycleResult

/**
 * One OS-driven `process()` invocation (`runProcessCycle`, capability `background-upload`) never
 * throws: the extension root hands its result across the ObjC boundary, where an escaping throwable
 * aborts the process instead of failing the cycle. Every throw degrades to `FAILED`, reported through
 * the hook for where it happened; the requeue rule itself is `RequeueWhilePendingTest`'s.
 */
class RunProcessCycleTest {

    @Test
    fun `a throwing cycle fails and reports the throwable as a cycle failure`() = runTest {
        val boom = IllegalStateException("cycle")
        var cycleFailure: Throwable? = null
        var lateFailure: Throwable? = null
        val out = runProcessCycle(
            run = { throw boom },
            pending = { 0 },
            onCycleFailed = { cycleFailure = it },
            onLateFailure = { lateFailure = it },
        )
        assertEquals(CycleResult.FAILED, out)
        assertSame(boom, cycleFailure)
        assertNull(lateFailure)
    }

    @Test
    fun `a completed cycle whose pending read throws fails instead of escaping`() = runTest {
        // The known abort path: the cycle SUCCEEDED, then the requeue's ledger read threw.
        val boom = IllegalStateException("ledger")
        var finished: CycleResult? = null
        var lateFailure: Throwable? = null
        val out = runProcessCycle(
            run = { CycleResult.COMPLETED },
            pending = { throw boom },
            onCycleFinished = { finished = it },
            onLateFailure = { lateFailure = it },
        )
        assertEquals(CycleResult.FAILED, out)
        assertEquals(CycleResult.COMPLETED, finished)
        assertSame(boom, lateFailure)
    }

    @Test
    fun `a throwing hook after the cycle fails instead of escaping`() = runTest {
        val boom = IllegalStateException("hook")
        var lateFailure: Throwable? = null
        val out = runProcessCycle(
            run = { CycleResult.SKIPPED },
            pending = { 0 },
            onCycleFinished = { throw boom },
            onLateFailure = { lateFailure = it },
        )
        assertEquals(CycleResult.FAILED, out)
        assertSame(boom, lateFailure)
    }

    @Test
    fun `a completed cycle with pending rows still requeues as PROCESSING`() = runTest {
        var reported: Int? = null
        val out = runProcessCycle(run = { CycleResult.COMPLETED }, pending = { 2 }, onRequeue = { reported = it })
        assertEquals(CycleResult.PROCESSING, out)
        assertEquals(2, reported)
    }

    @Test
    fun `every non-throwing result passes through the requeue rule unchanged`() = runTest {
        val expected = mapOf(
            CycleResult.COMPLETED to CycleResult.COMPLETED,
            CycleResult.PROCESSING to CycleResult.PROCESSING,
            CycleResult.SKIPPED to CycleResult.SKIPPED,
            CycleResult.FAILED to CycleResult.FAILED,
        )
        for ((result, answer) in expected) {
            assertEquals(answer, runProcessCycle(run = { result }, pending = { 0 }), "$result")
        }
    }
}
