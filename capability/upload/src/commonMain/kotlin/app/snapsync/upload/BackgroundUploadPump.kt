package app.snapsync.upload

import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app-driven (iOS 18–26.0) upload driver — the in-app reimplementation of the OS scheduler that,
 * on the PhotoKit tier, invokes `process()`. It drives [UploadCycle] via [runCycle] from four
 * triggers and re-arms via [scheduler].
 *
 * **Single-flight.** At most one cycle runs at a time; the ledger has a single writer, so two
 * concurrent cycles would double-write. A trigger arriving while a cycle is running does not start a
 * second cycle — it sets a trailing re-run, so the in-flight drain loops exactly once more after it
 * finishes (coalescing any number of overlapping triggers into one extra pass). Exit and the
 * `draining` reset happen in one critical section, so a trigger cannot slip into the gap between
 * "decide to stop" and "clear the flag" and be lost.
 *
 * **PROCESSING never busy-loops.** A cycle that returns [CycleResult.PROCESSING] (cap reached /
 * backpressure) is *not* immediately re-run — that would spin against a full cap. Instead an external
 * trigger re-invokes: in the foreground the next upload completion ([onUploadCompleted]) frees a slot;
 * in a background context the re-armed [scheduler] wakes the app.
 *
 * **Re-arm policy per trigger:**
 * - [onForeground] / [onUploadCompleted] — foreground: drain; do **not** schedule (completions drive
 *   re-invocation while the app is open).
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
    // Fired after every cycle so foreground status refreshes live (the app-driven analogue of the
    // PhotoKit extension's cross-process liveness ding — here an in-process ledger-counts re-read).
    // Best-effort: a failure never disturbs the cycle drain or the re-arm.
    private val onCycleComplete: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private var draining = false
    private var retrigger = false

    /** App entered the foreground: run the cycle and let completions drive further work. */
    suspend fun onForeground() = drive(scheduleOnProcessing = false, alwaysScheduleNext = false)

    /** An upload finished while foregrounded (a slot freed): pump the next batch. */
    suspend fun onUploadCompleted() = drive(scheduleOnProcessing = false, alwaysScheduleNext = false)

    /** Background `URLSession` completions were delivered on relaunch: record + top up, re-arm if work remains. */
    suspend fun onSessionEvents() = drive(scheduleOnProcessing = true, alwaysScheduleNext = false)

    /** A `BGProcessingTask` window opened: top up and re-submit the next heartbeat unconditionally. */
    suspend fun onBackgroundTask() = drive(scheduleOnProcessing = true, alwaysScheduleNext = true)

    private suspend fun drive(scheduleOnProcessing: Boolean, alwaysScheduleNext: Boolean) {
        // Single-flight admission: only one drain runs; overlapping triggers coalesce into a re-run.
        mutex.withLock {
            if (draining) {
                retrigger = true
                return
            }
            draining = true
        }

        var last = CycleResult.COMPLETED
        try {
            while (true) {
                last = runCycle()
                // Refresh status after every cycle (fire-and-forget; a failure must not break the drain).
                runCatching { onCycleComplete() }
                    .onFailure { log.w(it) { "status refresh after cycle failed" } }
                // Decide-and-exit atomically so a trigger arriving now is never lost: if one queued a
                // re-run, consume it and loop; otherwise clear `draining` and stop — both under the lock.
                val stop = mutex.withLock {
                    if (retrigger) {
                        retrigger = false
                        false
                    } else {
                        draining = false
                        true
                    }
                }
                if (stop) break
            }
        } catch (t: Throwable) {
            mutex.withLock { draining = false }
            throw t
        }

        // Re-arm outside the lock. The heartbeat always re-submits; a background relaunch re-arms iff
        // work remains; a foreground PROCESSING schedules nothing — it waits for the next completion.
        if (alwaysScheduleNext || (scheduleOnProcessing && last == CycleResult.PROCESSING)) {
            scheduler.scheduleNext()
        }
    }
}
