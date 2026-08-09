package app.snapsync.feature.upload

import app.snapsync.ports.BackgroundScheduler
import app.snapsync.ports.CycleResult

import app.snapsync.ports.LogScope
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app-driven (iOS 18–26.0) upload driver — the in-app reimplementation of the OS scheduler that,
 * on the PhotoKit tier, invokes `process()`. It drives [UploadCycle] via [runCycle] from six
 * triggers and re-arms via [scheduler].
 *
 * **Single-flight.** At most one cycle runs at a time; the ledger has a single writer, so two
 * concurrent cycles would double-write. A trigger arriving while a cycle is running does not start a
 * second cycle — it sets a trailing re-run, so the in-flight drain loops exactly once more after it
 * finishes (coalescing any number of overlapping triggers into one extra pass). Exit and the
 * clearing of [drainDone] happen in one critical section, so a trigger cannot slip into the gap between
 * "decide to stop" and "clear the flag" and be lost.
 *
 * **Coalescing does not discard the caller.** A coalesced trigger *awaits* the drain it extended and
 * then applies its own re-arm policy to that drain's result. It is not merely tidier: a caller that
 * returned immediately could not be the work an OS completion handler is held for, and it skipped a
 * `BGProcessingTask` re-submission that nothing else was going to make. Both were measured in the
 * field; see [drive].
 *
 * ⚠️ The await spans the **whole** drain loop, including the re-run it requested, so a coalesced caller
 * must never be re-entered from inside [runCycle] or [onCycleComplete] — that self-join would deadlock.
 * Nothing does: every trigger arrives from an OS callback on its own coroutine, and [onCycleComplete] is
 * a ledger-counts re-read with no route back here.
 *
 * The pump does not bound the wait, and nothing else bounds it either — a receipt bounds when the OS
 * *handler* is released, not how long this call takes. So a caller awaiting a drain that never ends waits
 * forever; what the receipt guarantees is only that the OS is answered on time regardless
 * (capability `ios-app-shell`).
 *
 * **PROCESSING never busy-loops.** A cycle that returns [CycleResult.PROCESSING] (cap reached /
 * backpressure) is *not* immediately re-run — that would spin against a full cap. Instead an external
 * trigger re-invokes: in the foreground the next upload completion ([onUploadCompleted]) frees a slot;
 * in a background context the re-armed [scheduler] wakes the app.
 *
 * **SKIPPED never re-arms — this overrides every trigger below.** A cycle that returns
 * [CycleResult.SKIPPED] declined because this membership contributes nothing (capability
 * `upload-lifecycle`). That answer cannot change until a provision or a permission grant, both of which
 * arrive as [onStart] — so scheduling anything here would wake the device forever to decline again. See
 * [shouldSchedule], which states the policy over the whole enum so a new variant must be decided, not
 * inherited.
 *
 * **Re-arm policy per trigger** (all subject to the SKIPPED rule above):
 * - [onStart] — the producer was started (a photo-access grant, or a membership provision): drain, and
 *   **always** schedule the next wake. This is the only trigger that *arms the first* `BGProcessingTask`.
 *   Without it nothing ever would: [onBackgroundTask] re-submits but needs a task to have already fired,
 *   and [onSessionEvents] re-arms only when an in-flight background transfer completes — so the tier's
 *   cold-start kick for "new photos captured while the app is closed" simply did not exist.
 * - [onForeground] — drain, and **always** schedule. A force-quit cancels every pending `BGTaskScheduler`
 *   request and iOS will not relaunch the app until the user opens it, so the re-submission chain is
 *   severed with nothing to restore it; reopening the app is exactly when the device is available to
 *   re-arm, and it used to be the one event that didn't.
 * - [onSilentPush] — a push for the **active event** (guarded upstream by `UploadPushReceiver`): drain,
 *   and **always** schedule. The heartbeat is deferred at OS discretion; a push is the reliable wake, and
 *   it clusters exactly when an event is live.
 * - [onUploadCompleted] — foreground: drain; do **not** schedule (completions drive re-invocation while
 *   the app is open).
 * - [onSessionEvents] — background relaunch (`handleEventsForBackgroundURLSession`): drain; schedule
 *   the next wake **iff** work remains ([CycleResult.PROCESSING]).
 * - [onBackgroundTask] — the `BGProcessingTask` heartbeat: drain; **always** re-submit the next task
 *   (it is one-shot; re-submitting each handler is what keeps the heartbeat alive to catch newly
 *   captured photos while an event remains joined).
 */
class BackgroundUploadPump(
    private val runCycle: suspend () -> CycleResult,
    private val scheduler: BackgroundScheduler,
    private val log: Logger = Logger.withTag("BackgroundUploadPump"),
    private val logScope: LogScope = LogScope.NoOp,
    // Fired after every cycle so foreground status refreshes live (the app-driven analogue of the
    // PhotoKit extension's cross-process liveness ding — here an in-process ledger-counts re-read).
    // Best-effort: a failure never disturbs the cycle drain or the re-arm.
    private val onCycleComplete: suspend () -> Unit = {},
) {
    private val mutex = Mutex()

    /**
     * The in-flight drain's result, or `null` when none is running — so "is a drain running" and "what
     * do coalescing callers await" are **one** fact rather than two that can disagree. Completed exactly
     * once, under [mutex], on every exit path the drain has.
     */
    private var drainDone: CompletableDeferred<CycleResult>? = null
    private var retrigger = false

    /**
     * The producer was started (`UploadProducer.start()` — a photo-access grant or a membership
     * provision): drain, and arm the heartbeat. The re-arm is unconditional because this is the one
     * trigger that can *create* the first `BGProcessingTask`; every other re-arm path presupposes one.
     */
    suspend fun onStart() = log.invocation(logScope, "pump.onStart") {
        drive(scheduleOnProcessing = false, alwaysScheduleNext = true)
    }

    /**
     * App entered the foreground: run the cycle, let completions drive further work — **and re-arm the
     * heartbeat**.
     *
     * The re-arm is not redundant with [onStart]. A force-quit cancels every pending `BGTaskScheduler`
     * request, and iOS does not relaunch a force-quit app until the user opens it — so the chain
     * `onStart → scheduleNext → each handler re-submits` is severed with nothing to restore it until the
     * next provision or permission grant. Reopening the app is precisely the moment the device is available
     * to be re-armed, and it was the one event that did not do so: new photos captured while closed then
     * waited for a membership transition that may never come.
     */
    suspend fun onForeground() = log.invocation(logScope, "pump.onForeground") {
        drive(scheduleOnProcessing = false, alwaysScheduleNext = true)
    }

    /**
     * A silent push arrived for this device's **active event**: drain, and re-arm.
     *
     * The reliable wake. `BGProcessingTask` is scheduled at the OS's discretion and is routinely deferred far
     * beyond its `earliestBeginDate` — least dependable exactly when an event is live and photos are being
     * taken. A push, by contrast, *clusters* on live events: it is emitted when another member's device drains
     * a cycle that completed an upload (capability `upload-completion-notify`). So the arrival of a peer's
     * photo is also this device's best opportunity to contribute its own.
     *
     * The active-event decision is **not** made here — it belongs to the tested receive seam
     * (`UploadPushReceiver`), mirroring the download arm's. This trigger presumes it has already passed, and
     * is orthogonal to the direction gate: a push for the active event on a download-only membership reaches
     * this method, and the cycle then declines with [CycleResult.SKIPPED], scheduling nothing.
     */
    suspend fun onSilentPush() = log.invocation(logScope, "pump.onSilentPush") {
        drive(scheduleOnProcessing = false, alwaysScheduleNext = true)
    }

    /**
     * The photo **selection changed** under a partial grant (capability `limited-photo-access`): the
     * one trigger that carries new discoverable work there. Drain and re-arm — like [onForeground],
     * because a selection change is the limited-mode analogue of "new photos exist": the user just
     * acted, the device is awake, and the heartbeat chain may be severed (this may be the first
     * trigger since a force-quit).
     */
    suspend fun onSelectionChanged() = log.invocation(logScope, "pump.onSelectionChanged") {
        drive(scheduleOnProcessing = false, alwaysScheduleNext = true)
    }

    /** An upload finished while foregrounded (a slot freed): pump the next batch. */
    suspend fun onUploadCompleted() = log.invocation(logScope, "pump.onUploadCompleted") {
        drive(scheduleOnProcessing = false, alwaysScheduleNext = false)
    }

    /** Background `URLSession` completions were delivered on relaunch: record + top up, re-arm if work remains. */
    suspend fun onSessionEvents() = log.invocation(logScope, "pump.onSessionEvents") {
        drive(scheduleOnProcessing = true, alwaysScheduleNext = false)
    }

    /** A `BGProcessingTask` window opened: top up and re-submit the next heartbeat unconditionally. */
    suspend fun onBackgroundTask() = log.invocation(logScope, "pump.onBackgroundTask") {
        drive(scheduleOnProcessing = true, alwaysScheduleNext = true)
    }

    /**
     * The re-arm decision for one drained cycle's [last] result — stated exhaustively, so a future
     * [CycleResult] variant is a compile error here rather than a policy someone silently inherits.
     *
     * [CycleResult.SKIPPED] schedules **nothing**, at every trigger — including [onBackgroundTask], whose
     * re-arm is otherwise unconditional. The cycle declined because this membership contributes nothing
     * (capability `upload-lifecycle`), and that answer will not change until a provision or a permission
     * grant re-arms via [onStart]. Re-arming here would wake the device forever to decline again.
     */
    private fun shouldSchedule(
        last: CycleResult,
        scheduleOnProcessing: Boolean,
        alwaysScheduleNext: Boolean,
    ): Boolean = when (last) {
        // Contributes nothing: never re-arm, whatever the trigger asked for.
        CycleResult.SKIPPED -> false
        // Work remains: re-arm in a background context; in the foreground a completion re-invokes.
        CycleResult.PROCESSING -> alwaysScheduleNext || scheduleOnProcessing
        CycleResult.COMPLETED, CycleResult.FAILED -> alwaysScheduleNext
    }

    private suspend fun drive(scheduleOnProcessing: Boolean, alwaysScheduleNext: Boolean) {
        // Single-flight admission: only one drain runs; overlapping triggers coalesce into a re-run.
        val inFlight = mutex.withLock {
            val running = drainDone
            if (running != null) {
                retrigger = true
                running
            } else {
                drainDone = CompletableDeferred()
                null
            }
        }

        // Coalesced: await the drain we just extended, then re-arm on ITS result with OUR policy.
        //
        // This used to `return` here, and that one statement dropped two obligations at once — both of
        // which cost the app future background wakes. (1) A caller that awaited nothing cannot be the
        // work an OS completion handler is held for: measured in the field, `pump.onSessionEvents`
        // exited in 0-2 ms on 30 of 30 background-relaunch wakes (27x 0 ms, 2x 1 ms, 1x 2 ms) while the
        // cycle it "drained" ran on for seconds, so the receipt around it held for nothing. (2) The re-arm was skipped, and the
        // `BGProcessingTask` is one-shot — an observed heartbeat fire coalesced, returned in 2 ms, and
        // re-submitted nothing, leaving the chain dead until the user next foregrounded the app.
        //
        // The result must come from the drain, not be assumed: this caller ran no cycle of its own, and
        // only that result answers "is there work left". A SKIPPED drain still arms nothing, from any
        // trigger, because [shouldSchedule] says so uniformly.
        if (inFlight != null) {
            val last = inFlight.await()
            if (shouldSchedule(last, scheduleOnProcessing, alwaysScheduleNext)) {
                scheduler.scheduleNext()
            }
            return
        }

        var last = CycleResult.COMPLETED
        try {
            while (true) {
                last = runCycle()
                // Refresh status after every cycle (fire-and-forget; a failure must not break the drain).
                runCatching { onCycleComplete() }
                    .onFailure { log.w(it) { "status refresh after cycle failed" } }
                // Decide-and-exit atomically so a trigger arriving now is never lost: if one queued a
                // re-run, consume it and loop; otherwise publish the result and stop — both under the
                // lock, so a coalescing caller either extends this drain or starts its own, never both.
                val stop = mutex.withLock {
                    if (retrigger) {
                        retrigger = false
                        false
                    } else {
                        drainDone?.complete(last)
                        drainDone = null
                        true
                    }
                }
                if (stop) break
            }
        } catch (t: Throwable) {
            // `NonCancellable` as defence in depth, and the honest status is: this does not fix a
            // reachable bug today, it removes a way for one to appear.
            //
            // [t] may itself be a `CancellationException`. If `Mutex.lock` had to suspend here it would
            // then throw before the cleanup ran, and [drainDone] would stay non-null for the process's
            // life — after which every later trigger, coalescing onto a deferred nothing can complete,
            // blocks forever. That is a far worse degradation than the flag-only version it replaced
            // ("work silently dropped"), which is why it is worth defending against cheaply.
            //
            // It is currently **unreachable**: no critical section in this class suspends while holding
            // the lock, and every composition injects a serial scope, so `lock()` always takes its
            // uncontended fast path, which does not check cancellation. Both halves of that are needed —
            // a multi-threaded scope would make contention possible, and a `suspend` call added inside
            // any `withLock` below would too. Neither is enforced by a compiler, so the wrapper stays.
            // No test covers this: with the invariant holding, the failure cannot be provoked through
            // this class's public surface (a mutation removing this wrapper survives the suite).
            withContext(NonCancellable) {
                mutex.withLock {
                    // Fail the waiters rather than leaving them parked forever: their work is this
                    // drain's work, and it did not happen. Where it surfaces differs by caller, and the
                    // two OS-wake shapes differ: `onBackgroundTask` and the silent push call `drive`
                    // from INSIDE their `OsReceipt.heldFor`, so the throw unwinds through the receipt
                    // and its `finally` releases; the background-session wake does not — its
                    // `onSessionEvents` runs as `BackgroundEventsReceipts`' drain work, which catches
                    // the throwable and releases from its own `finally` instead.
                    drainDone?.completeExceptionally(t)
                    drainDone = null
                    // Consume the trailing re-run too: it belonged to this drain, which is over.
                    // Leaving it set armed a phantom extra pass on whichever trigger came next.
                    retrigger = false
                }
            }
            throw t
        }

        // Re-arm outside the lock. The heartbeat always re-submits; a background relaunch re-arms iff
        // work remains; a foreground PROCESSING schedules nothing — it waits for the next completion; and
        // a SKIPPED cycle schedules nothing at all, however the trigger asked.
        if (shouldSchedule(last, scheduleOnProcessing, alwaysScheduleNext)) {
            scheduler.scheduleNext()
        }
    }
}
