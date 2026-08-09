package app.snapsync.ports

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The background-events receipt contract (capability `ios-app-shell`): a background-`URLSession`
 * completion handler is bounded from the moment the OS hands it over, released only after the work its
 * wake feeds, never orphaned by a second handover, and released on the lane UIKit requires.
 *
 * **Every handler here has a distinct identity, and each is asserted individually.** A shared counter
 * cannot see the failure this type exists to prevent: with one slot and two wakes, the first handler is
 * dropped and never called, and a test asserting "the handler was released once" passes while it
 * happens. That is not hypothetical — `parked/settle-imports-by-transaction`'s flagship test passed
 * while the duplicate it existed to prevent was being created, because its fake reused one identifier.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // advanceTimeBy / runCurrent
class BackgroundEventsReceiptsTest {

    /** Records which handlers were released, in order, by identity. */
    private class Handlers {
        val released = mutableListOf<String>()
        fun handler(id: String): () -> Unit = { released += id }
    }

    /**
     * A dispatcher that runs inline but counts its dispatches, so a release can prove it arrived
     * *through* the lane rather than merely happening while the lane existed.
     */
    private class CountingLane : CoroutineDispatcher() {
        var dispatches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            block.run()
        }
    }

    @Test
    fun `a handler is released after the work its drain feeds - not on the drain itself`() = runTest {
        val h = Handlers()
        val workGate = CompletableDeferred<Unit>()
        var workFinished = false
        val receipts = BackgroundEventsReceipts(
            scope = backgroundScope,
            entryPoint = "test",
            deadline = 20.seconds,
            work = { workGate.await(); workFinished = true },
        )

        receipts.adopt(h.handler("first-wake"))
        runCurrent()
        receipts.drained()
        runCurrent()
        assertEquals(emptyList(), h.released, "released on the drain signal, before the work it feeds")

        workGate.complete(Unit)
        runCurrent()
        assertTrue(workFinished)
        assertEquals(listOf("first-wake"), h.released)
    }

    @Test
    fun `the deadline runs from the handover - not from the drain`() = runTest {
        // The whole point of adopting at the handover: a session that never reports leaves the handler
        // bounded anyway. Nothing ever calls drained() here.
        val h = Handlers()
        val receipts = BackgroundEventsReceipts(
            scope = backgroundScope,
            entryPoint = "test",
            deadline = 20.seconds,
            work = { },
        )

        receipts.adopt(h.handler("stranded-wake"))
        advanceTimeBy(19.seconds)
        assertEquals(emptyList(), h.released, "released before its deadline")

        advanceTimeBy(2.seconds)
        assertEquals(listOf("stranded-wake"), h.released, "a drain that never came must still be bounded")
    }

    @Test
    fun `a second handover does not orphan the first — both are released`() = runTest {
        val h = Handlers()
        val workGate = CompletableDeferred<Unit>()
        val receipts = BackgroundEventsReceipts(
            scope = backgroundScope,
            entryPoint = "test",
            deadline = 20.seconds,
            work = { workGate.await() },
        )

        receipts.adopt(h.handler("wake-A"))
        runCurrent()
        receipts.adopt(h.handler("wake-B")) // arrives before wake-A's handler was released
        runCurrent()
        receipts.drained()
        workGate.complete(Unit)
        runCurrent()

        assertEquals(
            setOf("wake-A", "wake-B"),
            h.released.toSet(),
            "one drain must release every handler outstanding at that moment — a slot would have " +
                "overwritten wake-A and never called it",
        )
        assertEquals(2, h.released.size, "each handler released exactly once")
    }

    @Test
    fun `a handler adopted after a drain waits for the next drain`() = runTest {
        val h = Handlers()
        val receipts = BackgroundEventsReceipts(
            scope = backgroundScope,
            entryPoint = "test",
            deadline = 20.seconds,
            work = { },
        )

        receipts.drained() // an earlier wake's events, already delivered
        runCurrent()
        receipts.adopt(h.handler("later-wake"))
        runCurrent()
        assertEquals(emptyList(), h.released, "released by a drain that predates its own handover")

        receipts.drained()
        runCurrent()
        assertEquals(listOf("later-wake"), h.released)
    }

    @Test
    fun `a drain with no handler outstanding still runs its work`() = runTest {
        // A foreground drain has nobody waiting on it, and the cycle it feeds must still run.
        var runs = 0
        val receipts = BackgroundEventsReceipts(
            scope = backgroundScope,
            entryPoint = "test",
            deadline = 20.seconds,
            work = { runs++ },
        )

        receipts.drained()
        runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun `overlapping drains are serialised - the second cannot release against unfinished work`() = runTest {
        // The download arm's work is a DESTRUCTIVE read — it takes the outstanding-import list and
        // clears it — so an overlapping second drain would find nothing to wait for and release its
        // handler while the first drain's imports were still running.
        val h = Handlers()
        val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred())
        var started = 0
        val receipts = BackgroundEventsReceipts(
            scope = backgroundScope,
            entryPoint = "test",
            deadline = 20.seconds,
            work = { gates[started++].await() },
        )

        receipts.adopt(h.handler("wake-A"))
        runCurrent()
        receipts.drained() // drain A: takes the lock, blocks in its work
        runCurrent()
        receipts.adopt(h.handler("wake-B"))
        runCurrent()
        receipts.drained() // drain B: must queue behind A's work, not run alongside it
        runCurrent()

        assertEquals(1, started, "the second drain's work started while the first was still running")
        assertEquals(emptyList(), h.released)

        gates[0].complete(Unit)
        runCurrent()
        assertEquals(listOf("wake-A"), h.released, "only the first drain's handler may be released")
        assertEquals(2, started, "the second drain's work runs once the first releases the lock")

        gates[1].complete(Unit)
        runCurrent()
        assertEquals(listOf("wake-A", "wake-B"), h.released)
    }

    @Test
    fun `the release arrives through the injected lane`() = runTest {
        // UIKit requires this handler on the main thread, and the drain signal is delivered on a
        // session-owned queue — so the lane is the only thing putting the release where it belongs.
        val lane = CountingLane()
        var dispatchesSeenByHandler = -1
        val receipts = BackgroundEventsReceipts(
            scope = backgroundScope,
            entryPoint = "test",
            deadline = 20.seconds,
            work = { },
            releaseLane = lane,
        )

        receipts.adopt { dispatchesSeenByHandler = lane.dispatches }
        runCurrent()
        receipts.drained()
        runCurrent()

        assertEquals(1, lane.dispatches, "the release did not go through the lane")
        assertEquals(1, dispatchesSeenByHandler, "the handler ran outside the lane's dispatch")
    }

    @Test
    fun `a drain whose work throws still releases its handlers`() = runTest {
        val h = Handlers()
        val receipts = BackgroundEventsReceipts(
            scope = backgroundScope,
            entryPoint = "test",
            deadline = 20.seconds,
            work = { error("the cycle blew up") },
        )

        receipts.adopt(h.handler("wake-A"))
        runCurrent()
        receipts.drained()
        runCurrent()

        assertEquals(listOf("wake-A"), h.released, "an unanswered handler is worse than whatever threw")
    }
}
