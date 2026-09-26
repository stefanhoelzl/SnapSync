package app.snapsync.world

import app.snapsync.model.WakeId
import app.snapsync.ports.Completion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The heartbeat wake, as the `Wake` event port delivers it to the REAL composition (capability `sync-status`, "OS
 * completion handlers are released only after their work completes"; `background-upload`, "The tail runner
 * reimplements the OS scheduler"). These were `PlatformEntriesContract` clauses while a background task crossed the
 * inbound port; the wake is an event port now (phase 11f), so its handler's promises are pinned here, over the world
 * that composes the phone's graph, and a platform's task vocabulary (an identifier it does not know) beside each
 * adapter.
 */
class WakeWorldTest {

    private val joined = "22222222-2222-4222-8222-222222222222"

    /**
     * A completion handler as the operating system hands one over: it counts its releases, records whether [workDone]
     * held at the first, and lets the test deliver the operating system's expiry.
     */
    private class OsCompletion(private val workDone: () -> Boolean) : Completion {
        var releases = 0
        var doneAtRelease: Boolean? = null
        private var expiry: (() -> Unit)? = null
        private var expired = false

        override fun complete() {
            if (doneAtRelease == null) doneAtRelease = workDone()
            releases++
        }

        override fun onExpired(action: () -> Unit) {
            expiry = action
            if (expired) action()
        }

        fun expire() {
            expired = true
            expiry?.invoke()
        }
    }

    private suspend fun World.joinedWithAForeignPhoto(): World = apply {
        provision(joined)
        addForeignDevice("DEV-F", joined, listOf(World.foreignAsset("FQ")))
    }

    @Test
    fun a_heartbeat_wake_runs_the_tail_then_completes_and_re_arms() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        val completion = OsCompletion { w.operatorEngine.topUps > 0 && w.operatorEngine.walks > 0 }
        w.wake.fire(WakeId.Heartbeat, completion)
        waitFor { completion.releases > 0 }
        settle()
        assertEquals(1, completion.releases, "the completion is released exactly once")
        assertEquals(true, completion.doneAtRelease, "after the tail — the wake's work")
        assertEquals(1, w.heartbeatsScheduled, "the heartbeat re-submits itself after its tail")
    }

    @Test
    fun the_operating_systems_expiry_completes_the_wake_at_once_and_stops_the_tail() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        val finishUnit = CompletableDeferred<Unit>()
        w.operatorEngine.nextUnitGate = finishUnit
        val completion = OsCompletion { true }
        w.wake.fire(WakeId.Heartbeat, completion)
        waitFor { w.operatorEngine.topUps == 1 }
        completion.expire()
        // Completed while the unit is still in flight, exactly once.
        assertEquals(1, completion.releases, "released on the operating system's signal, not after the work")
        finishUnit.complete(Unit)
        waitFor { w.heartbeatsScheduled == 1 }
        settle()
        assertEquals(0, w.operatorEngine.walks, "no unit starts after the stop")
        assertEquals(1, w.heartbeatsScheduled, "a tail cut short still re-arms")
        assertEquals(1, completion.releases, "and the release after the work does not release it again")
    }

    @Test
    fun an_expiry_after_the_wake_ended_releases_nothing() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        val completion = OsCompletion { w.operatorEngine.topUps > 0 }
        w.wake.fire(WakeId.Heartbeat, completion)
        waitFor { completion.releases > 0 }
        completion.expire()
        settle()
        assertEquals(1, completion.releases)
    }

    @Test
    fun a_wake_whose_time_was_up_before_it_ran_is_released_and_runs_no_tail() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        val completion = OsCompletion { true }
        completion.expire()
        w.wake.fire(WakeId.Heartbeat, completion)
        settle()
        assertEquals(1, completion.releases, "an expiry registered late runs at once")
        assertEquals(0, w.operatorEngine.topUps, "and no tail is requested with no time left to run in")
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(10.seconds) {
        while (!condition()) delay(10)
    }

    /** A short pause, for asserting that something did NOT happen after a thing that did. */
    private suspend fun settle() = delay(200)

}
