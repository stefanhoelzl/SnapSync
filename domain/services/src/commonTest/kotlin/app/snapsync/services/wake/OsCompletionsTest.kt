package app.snapsync.services.wake

import app.snapsync.ports.Completion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The OS-handler contract (capability `sync-status`, "OS completion handlers are released only after their work
 * completes"): released after the wake's own work, on every path; released at once on the operating system's
 * expiry; exactly once whichever path gets there first; and never orphaned by a second handover.
 *
 * **Every handler here has a distinct identity, and each is asserted individually.** A shared counter cannot see the
 * failure a single stored slot causes: with two wakes the first handler is dropped and never called, and a test
 * asserting "a handler was released once" passes while it happens.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class OsCompletionsTest {

    /** Records which handlers were released, in order, by identity. */
    private class Handlers {
        val released = mutableListOf<String>()
        fun handler(id: String): Completion = completionOf { released += id }
    }

    @Test
    fun `a handler is released after the own work - not before it`() = runTest {
        val h = Handlers()
        val completions = OsCompletions("test")
        val gate = CompletableDeferred<Unit>()
        completions.adopt(h.handler("push"))
        val wake = launch { completions.releaseAfter { gate.await() } }
        runCurrent()
        assertTrue(h.released.isEmpty(), "released while the own work was still running")
        gate.complete(Unit)
        wake.join()
        assertEquals(listOf("push"), h.released)
    }

    @Test
    fun `the operating system's expiry releases at once and the own work runs on`() = runTest {
        val h = Handlers()
        val completions = OsCompletions("test")
        val gate = CompletableDeferred<Unit>()
        var workFinished = false
        val handover = completions.adopt(h.handler("task"))
        val wake = launch {
            completions.releaseAfter {
                gate.await()
                workFinished = true
            }
        }
        runCurrent()
        handover.releaseOnExpiry("test expiry")
        assertEquals(listOf("task"), h.released, "released on the signal, not after the work")
        assertTrue(handover.isReleased)
        assertFalse(workFinished, "the own work is not waited for")

        gate.complete(Unit)
        wake.join()
        assertTrue(workFinished, "and runs on until it finishes (or the process is suspended)")
        assertEquals(listOf("task"), h.released, "the release after the work does not release it again")
    }

    @Test
    fun `an expiry after the release releases nothing`() = runTest {
        val h = Handlers()
        val completions = OsCompletions("test")
        val handover = completions.adopt(h.handler("task"))
        completions.releaseAfter { }
        handover.releaseOnExpiry("late expiry")
        assertEquals(listOf("task"), h.released)
    }

    @Test
    fun `a second handover does not orphan the first - both are released`() = runTest {
        val h = Handlers()
        val completions = OsCompletions("test")
        completions.adopt(h.handler("wake-A"))
        completions.adopt(h.handler("wake-B"))
        completions.releaseAfter { }
        assertEquals(listOf("wake-A", "wake-B"), h.released)
    }

    @Test
    fun `a handler handed over during the own work waits for the next release`() = runTest {
        val h = Handlers()
        val completions = OsCompletions("test")
        completions.adopt(h.handler("wake-A"))
        completions.releaseAfter { completions.adopt(h.handler("wake-B")) }
        assertEquals(listOf("wake-A"), h.released, "its own wake's events have not been delivered yet")
        completions.releaseAfter { }
        assertEquals(listOf("wake-A", "wake-B"), h.released)
    }

    @Test
    fun `awaiting the release resumes on either path`() = runTest {
        val completions = OsCompletions("test")
        val first = completions.adopt(completionOf { })
        val second = completions.adopt(completionOf { })
        val a = async { first.awaitRelease() }
        runCurrent()
        assertFalse(a.isCompleted)
        first.releaseOnExpiry("test expiry")
        a.await()
        val b = async { second.awaitRelease() }
        completions.releaseAfter { }
        b.await()
    }

    @Test
    fun `own work that throws still releases its handlers`() = runTest {
        val h = Handlers()
        val completions = OsCompletions("test")
        completions.adopt(h.handler("wake-A"))
        runCatching { completions.releaseAfter { error("the flow blew up") } }
        assertEquals(listOf("wake-A"), h.released, "an unanswered handler is worse than whatever threw")
    }

    @Test
    fun `own work that is cancelled still releases its handlers`() = runTest {
        val h = Handlers()
        val completions = OsCompletions("test")
        completions.adopt(h.handler("wake-A"))
        val wake = launch { completions.releaseAfter { CompletableDeferred<Unit>().await() } }
        runCurrent()
        wake.cancel()
        wake.join()
        assertEquals(listOf("wake-A"), h.released)
    }
}

/** A [Completion] that runs [onComplete] each time it is completed — the holder's once-only is what is under test. */
private fun completionOf(onComplete: () -> Unit): Completion = object : Completion {
    override fun complete() = onComplete()
    override fun onExpired(action: () -> Unit) = Unit
}
