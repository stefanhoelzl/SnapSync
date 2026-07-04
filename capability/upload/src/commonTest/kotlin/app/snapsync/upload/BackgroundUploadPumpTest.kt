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
