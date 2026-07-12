package app.snapsync.upload

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundUploadPumpTest {

    private class FakeScheduler : BackgroundScheduler {
        var scheduled = 0
        var cancelled = 0
        override fun scheduleNext() { scheduled++ }
        override fun cancel() { cancelled++ }
    }

    /**
     * The enable path arms the heartbeat. `onStart` is the ONLY trigger that can submit the first
     * `BGProcessingTask`: `onBackgroundTask` re-submits but presupposes a task already fired, and
     * `onSessionEvents` re-arms only when an in-flight background transfer completes. Before this, the
     * enable path called `onForeground` — which never schedules — so on iOS 18–26.0 the heartbeat was
     * never armed at all and new photos captured while the app was closed had no cold-start kick.
     */
    @Test
    fun onStart_drains_and_arms_the_first_background_task() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        val pump = BackgroundUploadPump(
            runCycle = { runs++; CycleResult.COMPLETED },
            scheduler = scheduler,
        )

        pump.onStart()

        assertEquals(1, runs, "the enable path runs a cycle immediately")
        assertEquals(1, scheduler.scheduled, "…and arms exactly one BGProcessingTask")
    }

    /** The arm is unconditional — a fully-drained cycle (nothing left to do) still leaves a heartbeat. */
    @Test
    fun onStart_arms_even_when_the_cycle_drains_completely() = runTest {
        val scheduler = FakeScheduler()
        val pump = BackgroundUploadPump(
            runCycle = { CycleResult.COMPLETED }, // no work pending
            scheduler = scheduler,
        )

        pump.onStart()

        // A COMPLETED cycle means nothing to upload *now* — but the heartbeat is what catches photos
        // captured later, while the app is closed. Arming only on PROCESSING would lose exactly that.
        assertEquals(1, scheduler.scheduled)
    }

    /** Foreground entry still must NOT schedule — completions drive re-invocation while the app is open. */
    @Test
    fun onForeground_does_not_arm_a_task() = runTest {
        val scheduler = FakeScheduler()
        val pump = BackgroundUploadPump(
            runCycle = { CycleResult.COMPLETED },
            scheduler = scheduler,
        )

        pump.onForeground()

        assertEquals(0, scheduler.scheduled, "foreground drains; it does not schedule background wakes")
    }

    /** A trigger arriving mid-cycle coalesces into exactly one trailing re-run — never a parallel cycle. */
    @Test
    fun coalescesConcurrentTriggersIntoOneRerun() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        lateinit var pump: BackgroundUploadPump
        pump = BackgroundUploadPump(
            runCycle = {
                runs++
                if (runs == 1) pump.onUploadCompleted() // arrives while the first cycle is running
                CycleResult.COMPLETED
            },
            scheduler = scheduler,
        )
        pump.onForeground()
        assertEquals(2, runs) // the mid-run trigger produced one extra pass, not two cycles at once
    }

    /** Every cycle triggers the status refresh (the in-process liveness signal), once per pass. */
    @Test
    fun refreshesStatusAfterEachCycle() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        var refreshes = 0
        lateinit var pump: BackgroundUploadPump
        pump = BackgroundUploadPump(
            runCycle = {
                runs++
                if (runs == 1) pump.onUploadCompleted() // force a trailing re-run → two cycles
                CycleResult.COMPLETED
            },
            scheduler = scheduler,
            onCycleComplete = { refreshes++ },
        )
        pump.onForeground()
        assertEquals(2, runs)
        assertEquals(2, refreshes) // one refresh per cycle, both passes
    }

    /** A refresh failure never breaks the drain or the re-arm. */
    @Test
    fun refreshFailureDoesNotBreakTheDrain() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        val pump = BackgroundUploadPump(
            runCycle = { runs++; CycleResult.COMPLETED },
            scheduler = scheduler,
            onCycleComplete = { error("refresh blew up") },
        )
        pump.onForeground() // must not throw
        assertEquals(1, runs)
    }

    /** Foreground PROCESSING does not busy-loop or schedule; the next completion re-invokes. */
    @Test
    fun processingInForegroundWaitsForCompletion() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        val pump = BackgroundUploadPump(runCycle = { runs++; CycleResult.PROCESSING }, scheduler = scheduler)

        pump.onForeground()
        assertEquals(1, runs)               // did not loop on PROCESSING
        assertEquals(0, scheduler.scheduled) // foreground waits for a completion, schedules nothing

        pump.onUploadCompleted()
        assertEquals(2, runs)               // the completion re-invokes the cycle
    }

    /** A background relaunch re-arms the scheduler when work remains. */
    @Test
    fun processingOnSessionEventsSchedulesNext() = runTest {
        val scheduler = FakeScheduler()
        val pump = BackgroundUploadPump(runCycle = { CycleResult.PROCESSING }, scheduler = scheduler)

        pump.onSessionEvents()
        assertEquals(1, scheduler.scheduled)
    }

    /** A drained background relaunch does not re-arm (nothing left to wake for). */
    @Test
    fun completedOnSessionEventsDoesNotSchedule() = runTest {
        val scheduler = FakeScheduler()
        val pump = BackgroundUploadPump(runCycle = { CycleResult.COMPLETED }, scheduler = scheduler)

        pump.onSessionEvents()
        assertEquals(0, scheduler.scheduled)
    }

    /** The BGProcessingTask heartbeat always re-submits the next task, even when fully drained. */
    @Test
    fun backgroundTaskAlwaysResubmits() = runTest {
        val scheduler = FakeScheduler()
        val pump = BackgroundUploadPump(runCycle = { CycleResult.COMPLETED }, scheduler = scheduler)

        pump.onBackgroundTask()
        assertEquals(1, scheduler.scheduled)
    }

    /** A drained foreground pump idles: one cycle, no schedule. */
    @Test
    fun completedForegroundIdles() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        val pump = BackgroundUploadPump(runCycle = { runs++; CycleResult.COMPLETED }, scheduler = scheduler)

        pump.onForeground()
        assertEquals(1, runs)
        assertEquals(0, scheduler.scheduled)
    }
}
