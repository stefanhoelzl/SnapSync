@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.scheduling

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Two runs that must not overlap: a join holds the membership change, and a sweep of a failed leave's yields to it. */
class ExclusionTest {

    @Test
    fun `work yielding to an exclusion is skipped while it is held - and says so - and runs once released`() = runTest {
        val log = RecordingLog().also { it.install() }
        val change = Exclusion("membership change")
        var swept = 0
        change.release() // a hold never taken releases nothing
        change.unlessHeld("leftovers") { swept++ }

        change.hold()
        change.unlessHeld("leftovers") { swept++ }
        assertEquals(1, swept, "a holder keeps what the work would take")
        assertEquals(listOf("skipped: held elsewhere"), log.events())

        change.release()
        change.unlessHeld("leftovers") { swept++ }
        assertEquals(2, swept)
    }

    @Test
    fun `a hold waits for work already running`() = runTest {
        val change = Exclusion("membership change")
        val sweeping = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sweep = launch {
            change.unlessHeld("leftovers") {
                sweeping.complete(Unit)
                release.await()
            }
        }
        sweeping.await()
        val join = launch { change.hold() }
        runCurrent()
        assertFalse(join.isCompleted, "the hold waits")
        release.complete(Unit)
        sweep.join()
        join.join()
        change.release()
    }

    @Test
    fun `failing work releases what it held`() = runTest {
        val change = Exclusion("membership change")
        runCatching { change.unlessHeld("failing") { error("the sweep failed") } }
        var swept = false
        change.unlessHeld("leftovers") { swept = true }
        assertTrue(swept)
    }
}
