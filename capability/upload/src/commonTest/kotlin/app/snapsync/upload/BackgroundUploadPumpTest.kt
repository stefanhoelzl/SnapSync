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
