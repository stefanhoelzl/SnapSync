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
 * Owns what the two wakes ARE — their delays and the heartbeat's network requirement — over the thin [Wake] port.
 * **When** each is armed is the tail runner's rule (`feature/upload/TailRunner`); a disarm cancels both.
 *
 * - [arm] requests the timed heartbeat.
 * - [watchLibrary] requests the library-change wake: a standing "wake me when a photo is added", re-requested after
 *   every tail while the membership contributes. A platform without it answers [ScheduleResult.Unsupported] — iOS,
 *   whose library-change wake is the upload extension — which is not a failure and needs no branch here; the answer
 *   says whether a watch now stands.
 * - [cancel] withdraws both, on every platform.
 */
class Heartbeat(
    private val wake: Wake,
    private val log: Logger = Logger.withTag("Heartbeat"),
) {
    /**
     * Ensure the next heartbeat is requested. One-shot on every platform, so this is called to re-submit; the port's
     * requests are idempotent, so a repeated arm replaces the pending request rather than stacking one.
     */
    fun arm() {
        request(WakeId.Heartbeat)
    }

    /**
     * Ensure a library-change wake is requested, and answer whether one now stands — `false` where the platform has
     * none (iOS) or refused it. One-shot like the heartbeat, and idempotent.
     */
    fun watchLibrary(): Boolean = request(WakeId.LibraryChanged)

    private fun request(id: WakeId): Boolean = when (val answer = wake.schedule(id, triggerFor(id))) {
        ScheduleResult.Scheduled -> true
        ScheduleResult.Unsupported -> false
        // Not silent (`docs/architecture.md`, "Absence is never silent"): a refused heartbeat is a device that
        // uploads only while the app is open or a push wakes it.
        is ScheduleResult.Refused -> false.also { log.w { "the operating system refused the $id wake: ${answer.detail}" } }
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
