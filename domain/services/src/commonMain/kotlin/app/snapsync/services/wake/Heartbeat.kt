package app.snapsync.services.wake

import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.Wake
import co.touchlab.kermit.Logger
import kotlin.time.Duration.Companion.seconds

/**
 * **The app uploader's heartbeat** (capability `background-upload`, "Photos upload without the app being opened"):
 * the timed background wake that keeps the process tail running while work remains and the app is closed — the one
 * wake of the app's own asking, since the download backstop went (decision record `changes/own-work-per-wake`).
 *
 * Owns what the heartbeat IS — its delay and its network requirement — over the thin [Wake] port. **When** it is armed
 * is the tail runner's re-arm rule (`feature/upload/TailRunner`); a disarm cancels it.
 *
 * [arm] and [cancel] act on **every** [WakeId], on every platform: a platform without a kind of wake answers
 * [ScheduleResult.Unsupported], which is not a failure and needs no branch here. So iOS arms its `BGProcessingTask`
 * and refuses the library-change wake, and an Android build arms both with no change above the port.
 */
class Heartbeat(
    private val wake: Wake,
    private val log: Logger = Logger.withTag("Heartbeat"),
) {
    /**
     * Ensure the next wakes are requested. One-shot on every platform, so this is called to re-submit; the port's
     * requests are idempotent, so a repeated arm replaces the pending request rather than stacking one.
     */
    fun arm() {
        for (id in WakeId.entries) {
            when (val answer = wake.schedule(id, triggerFor(id))) {
                ScheduleResult.Scheduled, ScheduleResult.Unsupported -> Unit
                // Not silent (`docs/architecture.md`, "Absence is never silent"): a refused heartbeat is a device that
                // uploads only while the app is open or a push wakes it.
                is ScheduleResult.Refused -> log.w { "the operating system refused the $id wake: ${answer.detail}" }
            }
        }
    }

    /** Withdraw every pending wake (a leave, or a disarm). */
    fun cancel() {
        WakeId.entries.forEach(wake::cancel)
    }

    private companion object {
        /**
         * A small delay, so a burst of re-arms coalesces into roughly one wake; the operating system treats it as a
         * lower bound and schedules opportunistically after it.
         */
        val EARLIEST = 60.seconds

        /** The most a library-change wake may lag the change that prompted it. */
        val LIBRARY_CHANGE_DELAY = 60.seconds

        /**
         * The trigger for [id]. The heartbeat needs the network (its work uploads) and not external power, so the
         * operating system grants windows often enough to drain a first whole-library upload.
         */
        fun triggerFor(id: WakeId): WakeTrigger = when (id) {
            WakeId.Heartbeat -> WakeTrigger.After(earliest = EARLIEST, requiresNetwork = true)
            WakeId.LibraryChanged -> WakeTrigger.LibraryChange(maxDelay = LIBRARY_CHANGE_DELAY)
        }
    }
}
