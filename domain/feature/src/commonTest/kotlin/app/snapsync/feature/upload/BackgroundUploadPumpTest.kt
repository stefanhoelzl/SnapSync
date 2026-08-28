package app.snapsync.feature.upload

import app.snapsync.ports.BackgroundScheduler
import app.snapsync.ports.CycleResult

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent on the test scheduler
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

    /**
     * Foreground entry now **arms** the heartbeat. This reverses the previous policy ("foreground drains; it
     * does not schedule background wakes"), which was true but incomplete: it reasoned only about the app
     * being open, where completions do drive re-invocation.
     *
     * The case it missed is the app being *closed again*. A force-quit cancels every pending
     * `BGTaskScheduler` request, and iOS does not relaunch a force-quit app until the user opens it — so the
     * chain `onStart → scheduleNext → each handler re-submits` is severed, and nothing restores it until the
     * next provision or permission grant, which may never come. Reopening the app is precisely when the
     * device is available to be re-armed, and it was the one event that didn't.
     */
    @Test
    fun onForeground_arms_the_heartbeat_so_a_force_quit_is_recoverable() = runTest {
        val scheduler = FakeScheduler()
        val pump = BackgroundUploadPump(
            runCycle = { CycleResult.COMPLETED },
            scheduler = scheduler,
        )

        // No prior onStart: this models reopening after a force-quit, with no membership transition to
        // arm anything. Before, this left the device with no heartbeat at all.
        pump.onForeground()

        assertEquals(1, scheduler.scheduled, "reopening the app re-arms the heartbeat")
    }

    /**
     * A trigger arriving mid-cycle coalesces into exactly one trailing re-run — never a parallel cycle.
     *
     * The trigger arrives from **another coroutine**, as every real one does — an OS callback, a
     * delegate queue, a push. It used to be invoked from inside `runCycle`, which no production path
     * does and which a coalesced caller that awaits the drain would deadlock on: it would be waiting
     * for the very cycle whose body is doing the waiting.
     */
    @Test
    fun coalescesConcurrentTriggersIntoOneRerun() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        val firstCycle = CompletableDeferred<Unit>()
        val pump = BackgroundUploadPump(
            runCycle = {
                runs++
                if (runs == 1) firstCycle.await() // hold the first cycle open
                CycleResult.COMPLETED
            },
            scheduler = scheduler,
        )

        val drain = launch { pump.onForeground() }
        runCurrent() // the first cycle is now in flight, parked

        var coalescedReturned = false
        val coalesced = launch {
            pump.onUploadCompleted()
            coalescedReturned = true
        }
        runCurrent()
        assertFalse(coalescedReturned, "a coalesced trigger returned while the drain it extended ran on")

        firstCycle.complete(Unit)
        drain.join()
        coalesced.join()

        assertEquals(2, runs) // the mid-run trigger produced one extra pass, not two cycles at once
        assertTrue(coalescedReturned, "the coalesced trigger must return once the drain has ended")
    }

    /**
     * A drain that is cancelled mid-cycle must not wedge the pump.
     *
     * Coalescing callers now *await* the in-flight drain instead of returning, which changes what a lost
     * cleanup costs: a `drainDone` left non-null is no longer "work silently dropped" but "every trigger
     * blocks forever" — foreground, start, push, selection, session events and heartbeat alike.
     */
    @Test
    fun aCancelledDrainDoesNotWedgeTheNextTrigger() = runTest {
        val scheduler = FakeScheduler()
        val firstCycle = CompletableDeferred<Unit>()
        var runs = 0
        val pump = BackgroundUploadPump(
            runCycle = {
                runs++
                if (runs == 1) firstCycle.await()
                CycleResult.COMPLETED
            },
            scheduler = scheduler,
        )

        val drain = launch { pump.onForeground() }
        runCurrent()
        drain.cancel() // the drain's coroutine dies mid-cycle
        runCurrent()

        // If the cleanup was lost, this parks on a deferred nothing can complete and the timeout fires.
        withTimeout(10.seconds) { pump.onBackgroundTask() }
        assertEquals(2, runs, "the later trigger must run its own drain, not inherit the dead one")
        assertEquals(1, scheduler.scheduled, "and keep its own re-arm")
    }

    /**
     * The heartbeat's re-arm is unconditional and the `BGProcessingTask` is one-shot, so a coalesced
     * fire that re-submitted nothing severed the chain until the user next foregrounded the app. Seen
     * in the field: `← pump.onBackgroundTask (2ms)` with no cycle between entry and exit and no
     * `scheduleNext` after it.
     */
    @Test
    fun coalescedBackgroundTaskStillResubmits() = runTest {
        val scheduler = FakeScheduler()
        val firstCycle = CompletableDeferred<Unit>()
        var runs = 0
        val pump = BackgroundUploadPump(
            runCycle = {
                runs++
                if (runs == 1) firstCycle.await()
                CycleResult.COMPLETED
            },
            scheduler = scheduler,
        )

        // A completion-driven drain: its own policy schedules nothing, so any re-arm below is the
        // coalesced heartbeat's and nothing else's.
        val drain = launch { pump.onUploadCompleted() }
        runCurrent()
        val heartbeat = launch { pump.onBackgroundTask() }
        runCurrent()

        firstCycle.complete(Unit)
        drain.join()
        heartbeat.join()

        assertEquals(1, scheduler.scheduled, "the coalesced heartbeat did not re-submit the next task")
    }

    /** A coalesced trigger re-arms on the drain's result, not on an assumption about it. */
    @Test
    fun coalescedRelaunchTriggerRearmsOnlyOnRemainingWork() = runTest {
        val testScope = this
        suspend fun scheduledAfterDrainEnding(result: CycleResult): Int {
            val scheduler = FakeScheduler()
            val firstCycle = CompletableDeferred<Unit>()
            var runs = 0
            val pump = BackgroundUploadPump(
                runCycle = {
                    runs++
                    if (runs == 1) firstCycle.await()
                    result
                },
                scheduler = scheduler,
            )
            val drain = testScope.launch { pump.onUploadCompleted() }
            testScope.runCurrent()
            val coalesced = testScope.launch { pump.onSessionEvents() }
            testScope.runCurrent()
            firstCycle.complete(Unit)
            drain.join()
            coalesced.join()
            return scheduler.scheduled
        }

        assertEquals(1, scheduledAfterDrainEnding(CycleResult.PROCESSING), "work remained; nothing re-armed")
        assertEquals(0, scheduledAfterDrainEnding(CycleResult.COMPLETED), "nothing remained; it re-armed anyway")
        // SKIPPED overrides every trigger's own policy, coalesced or not.
        assertEquals(0, scheduledAfterDrainEnding(CycleResult.SKIPPED), "a declining membership was re-armed")
    }

    /** Every cycle triggers the status refresh (the in-process liveness signal), once per pass. */
    @Test
    fun refreshesStatusAfterEachCycle() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        var refreshes = 0
        val firstCycle = CompletableDeferred<Unit>()
        val pump = BackgroundUploadPump(
            runCycle = {
                runs++
                if (runs == 1) firstCycle.await()
                CycleResult.COMPLETED
            },
            scheduler = scheduler,
            onCycleComplete = { refreshes++ },
        )

        val drain = launch { pump.onForeground() }
        runCurrent()
        val coalesced = launch { pump.onUploadCompleted() } // force a trailing re-run → two cycles
        runCurrent()
        firstCycle.complete(Unit)
        drain.join()
        coalesced.join()

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

    /**
     * Foreground `PROCESSING` does not busy-loop — the load-bearing half, unchanged: re-running immediately
     * would spin against a full cap. The next completion re-invokes.
     *
     * It now also arms the heartbeat (see [onForeground_arms_the_heartbeat_so_a_force_quit_is_recoverable]);
     * that is the trigger's policy, not a statement about `PROCESSING`.
     */
    @Test
    fun processingInForegroundWaitsForCompletion() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        val pump = BackgroundUploadPump(runCycle = { runs++; CycleResult.PROCESSING }, scheduler = scheduler)

        pump.onForeground()
        assertEquals(1, runs)               // did not loop on PROCESSING — the property that matters here

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

    /** A drained foreground pump runs exactly one cycle (and arms — see the foreground policy above). */
    @Test
    fun completedForegroundRunsOneCycle() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        val pump = BackgroundUploadPump(runCycle = { runs++; CycleResult.COMPLETED }, scheduler = scheduler)

        pump.onForeground()
        assertEquals(1, runs)
    }

    // ---- SKIPPED: a membership that contributes nothing must never hold background work ----------------

    /**
     * **SKIPPED never re-arms, at ANY trigger** (capability `upload-lifecycle`).
     *
     * `onBackgroundTask` is the one that matters: its re-arm is otherwise *unconditional*, so without this a
     * download-only device would wake, decline, re-submit, wake, decline… forever. `onStart` matters for the
     * same reason. This is the whole of "no bgtasks for a non-contributor", and it is expressed once — in
     * `shouldSchedule` — rather than at each trigger.
     */
    @Test
    fun skipped_never_arms_a_task_from_any_trigger() = runTest {
        suspend fun scheduledAfter(trigger: suspend BackgroundUploadPump.() -> Unit): Int {
            val scheduler = FakeScheduler()
            val pump = BackgroundUploadPump(runCycle = { CycleResult.SKIPPED }, scheduler = scheduler)
            pump.trigger()
            return scheduler.scheduled
        }

        assertEquals(0, scheduledAfter { onStart() }, "onStart: unconditional re-arm still yields to SKIPPED")
        assertEquals(0, scheduledAfter { onBackgroundTask() }, "the heartbeat must not re-submit itself")
        assertEquals(0, scheduledAfter { onForeground() })
        assertEquals(0, scheduledAfter { onSilentPush() })
        assertEquals(0, scheduledAfter { onSessionEvents() })
        assertEquals(0, scheduledAfter { onUploadCompleted() })
        assertEquals(0, scheduledAfter { onSelectionChanged() })
    }

    /** A declined cycle still *runs* once — the cycle is what decides, so the pump must call it to find out. */
    @Test
    fun skipped_still_invokes_the_cycle_once() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        val pump = BackgroundUploadPump(runCycle = { runs++; CycleResult.SKIPPED }, scheduler = scheduler)

        pump.onBackgroundTask()

        // The pump holds no posture of its own: it asks the cycle, which reads the membership's
        // Contribution. One call, one answer, no re-arm.
        assertEquals(1, runs)
        assertEquals(0, scheduler.scheduled)
    }

    // ---- silent push: the reliable wake ---------------------------------------------------------------

    /**
     * A silent push for the active event drains and arms. `BGProcessingTask` is deferred at OS discretion —
     * routinely far past its `earliestBeginDate`, and least dependable exactly when an event is live. A push
     * arrives *because* a peer's device completed an upload (capability `upload-completion-notify`), so it
     * clusters on live events, which is when this device most likely has photos of its own to contribute.
     *
     * The active-event guard lives in `UploadPushReceiver`, not here — this trigger presumes it passed.
     */
    @Test
    fun onSilentPush_drains_and_arms() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        val pump = BackgroundUploadPump(runCycle = { runs++; CycleResult.COMPLETED }, scheduler = scheduler)

        pump.onSilentPush()

        assertEquals(1, runs)
        assertEquals(1, scheduler.scheduled)
    }

    /** A push arriving mid-cycle coalesces like every other trigger — never a parallel ledger writer. */
    @Test
    fun onSilentPush_coalesces_with_an_in_flight_cycle() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        val firstCycle = CompletableDeferred<Unit>()
        val pump = BackgroundUploadPump(
            runCycle = {
                runs++
                if (runs == 1) firstCycle.await()
                CycleResult.COMPLETED
            },
            scheduler = scheduler,
        )

        val drain = launch { pump.onBackgroundTask() }
        runCurrent()

        // The push arrives from its own coroutine, as APNs delivery does.
        var pushReturned = false
        val push = launch {
            pump.onSilentPush()
            pushReturned = true
        }
        runCurrent()
        assertFalse(pushReturned, "the push returned while the drain it extended was still running")

        firstCycle.complete(Unit)
        drain.join()
        push.join()

        assertEquals(2, runs) // one trailing re-run, not two concurrent cycles
        assertTrue(pushReturned)
    }

    // ---- the selection change: the partial grant's only "new photos exist" ------------------------

    /**
     * Under a partial grant the library walk is not available, so a selection change is the ONE trigger
     * that carries new discoverable work (capability `limited-photo-access`). It drains and arms like
     * [BackgroundUploadPump.onForeground]: the user has just acted, the device is awake, and the
     * heartbeat chain may be severed — this may be the first trigger since a force-quit.
     */
    @Test
    fun onSelectionChanged_drains_and_arms() = runTest {
        val scheduler = FakeScheduler()
        var runs = 0
        val pump = BackgroundUploadPump(runCycle = { runs++; CycleResult.COMPLETED }, scheduler = scheduler)

        pump.onSelectionChanged()

        assertEquals(1, runs)
        assertEquals(1, scheduler.scheduled)
    }
}
