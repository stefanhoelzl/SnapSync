package app.snapsync.ports

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The OS-receipt contract (capability `ios-app-shell`): a completion handler is released only after the
 * work its wake triggered has finished, or after a deadline — and exactly once, on every path.
 *
 * These are the tests the shell could not have: `:app:*` Kotlin is wiring-only and untested by rule,
 * which is why the type lives in `:domain` at all.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // advanceTimeBy on the test scheduler
class OsReceiptTest {

    private class Handler {
        var releases = 0
        val released: Boolean get() = releases > 0
    }

    private fun receipt(h: Handler, deadline: kotlin.time.Duration = 20.seconds) =
        OsReceipt(entryPoint = "test", deadline = deadline, release = { h.releases++ })

    @Test
    fun `the handler is released after the work and never before it`() = runTest {
        val h = Handler()
        val gate = CompletableDeferred<Unit>()
        var workFinished = false

        val held = launch {
            receipt(h).heldFor {
                gate.await()
                workFinished = true
            }
        }
        advanceTimeBy(1.seconds)
        assertFalse(h.released, "released while its work was still running")

        gate.complete(Unit)
        held.join()
        assertTrue(workFinished)
        assertEquals(1, h.releases)
    }

    @Test
    fun `the deadline releases the handler and leaves the work running`() = runTest {
        // The whole point of the bound: a stalled unit of work must never turn a held receipt into a
        // termination. Releasing is allowed; cancelling the work is not.
        val h = Handler()
        val gate = CompletableDeferred<Unit>()
        var workFinished = false

        val held = launch {
            receipt(h, deadline = 5.seconds).heldFor {
                gate.await()
                workFinished = true
            }
        }
        advanceTimeBy(6.seconds)
        assertEquals(1, h.releases, "the deadline must release the handler")
        assertFalse(workFinished, "the work must NOT have been cancelled")
        assertTrue(held.isActive, "heldFor still tracks the work it let go of")

        gate.complete(Unit)
        held.join()
        assertTrue(workFinished, "the work ran to completion after the handler was released")
        assertEquals(1, h.releases, "still exactly once")
    }

    @Test
    fun `a throwing body still releases the handler exactly once`() = runTest {
        val h = Handler()
        assertFailsWith<IllegalStateException> {
            receipt(h).heldFor { error("the work blew up") }
        }
        assertEquals(1, h.releases, "an unanswered handler costs the app its future background wakes")
    }

    @Test
    fun `work that finishes immediately releases exactly once`() = runTest {
        val h = Handler()
        receipt(h).heldFor { }
        assertEquals(1, h.releases)
    }
}
