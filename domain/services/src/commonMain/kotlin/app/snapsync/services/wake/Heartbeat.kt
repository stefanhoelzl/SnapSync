package app.snapsync.services.wake

import app.snapsync.model.ScheduleResult
import app.snapsync.model.TransferNetwork
import app.snapsync.model.WakeCadence
import app.snapsync.model.WakeId
import app.snapsync.model.WakeNetwork
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.Wake
import co.touchlab.kermit.Logger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * **The app's heartbeat** (capabilities `background-upload`, "Photos upload without the app being opened", and
 * `receiving-photos`, "New photos are announced by a silent wake, and never only by it"): the timed background wake
 * that keeps the process tail running while the app is closed — **busy** while work remains, **idle** otherwise, and
 * pending for as long as the device is joined (decision record `changes/timely-background-receiving`, D1).
 *
 * Owns what the wakes ARE — their delays and the heartbeat's network requirement — over the thin [Wake] port.
 * **When** and at which cadence each is armed is the tail runner's rule (`feature/upload/TailRunner`); a disarm
 * cancels every one.
 *
 * - [arm] requests the timed heartbeat at a [WakeCadence]: one heartbeat is pending at a time, so a request at one
 *   cadence replaces a pending one at the other.
 * - [watchLibrary] requests the library-change wake: a standing "wake me when a photo is added", re-requested after
 *   every tail while the membership contributes. A platform without it answers [ScheduleResult.Unsupported] — iOS,
 *   whose library-change wake is the upload extension — which is not a failure and needs no branch here; the answer
 *   says whether a watch now stands.
 * - [cancel] withdraws both, on every platform.
 */
class Heartbeat(
    private val wake: Wake,
    /**
     * The networks the membership's photo transfers may use (capability `mobile-data`), read at each arm: a BUSY
     * heartbeat for a member who keeps photos off mobile data waits for an unrestricted network, because the transfers
     * it would run wait for one anyway — and where nothing else holds an upload for it (Android's in-process uploader)
     * this wake is what resumes it once the phone reaches Wi-Fi. An idle heartbeat moves no photo, so it keeps waiting
     * for any connection, and silent wakes are unaffected.
     */
    private val transferNetwork: () -> TransferNetwork,
    private val log: Logger = Logger.withTag("Heartbeat"),
) {
    /**
     * Ensure the next heartbeat is requested. One-shot on every platform, so this is called to re-submit; the port's
     * requests are idempotent, so a repeated arm replaces the pending request rather than stacking one.
     */
    fun arm(cadence: WakeCadence) {
        val trigger = triggerAt(cadence, transferNetwork())
        // The field's only record of the cadence a device keeps (decision record `changes/timely-background-receiving`).
        if (request(WakeId.Heartbeat, trigger)) {
            log.i { "heartbeat armed: ${cadence.name.lowercase()}, no sooner than ${trigger.earliest}, network ${trigger.network}" }
        }
    }

    /**
     * Ensure a library-change wake is requested, and answer whether one now stands — `false` where the platform has
     * none (iOS) or refused it. One-shot like the heartbeat, and idempotent.
     */
    fun watchLibrary(): Boolean = request(WakeId.LibraryChanged, WakeTrigger.LibraryChange(maxDelay = LIBRARY_CHANGE_DELAY))

    private fun request(id: WakeId, trigger: WakeTrigger): Boolean = when (val answer = wake.schedule(id, trigger)) {
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

    internal companion object {
        /**
         * The busy delay: small, so a burst of re-arms coalesces into roughly one wake; the operating system treats it
         * as a lower bound and schedules opportunistically after it.
         */
        val BUSY_EARLIEST = 60.seconds

        /**
         * The idle delay: nothing is left to do, so the wake only looks in — for others' photos and the event's close,
         * each checked at most once an hour anyway (decision record `changes/timely-background-receiving`, D4–D5).
         */
        val IDLE_EARLIEST = 1.hours

        /** The most a library-change wake may lag the change that prompted it. */
        val LIBRARY_CHANGE_DELAY = 60.seconds

        /**
         * The heartbeat's trigger at [cadence]. It needs the network (its work uploads and reads the event) and not
         * external power, so the operating system grants windows often enough to drain a first whole-library upload.
         * A busy one for photos held to unrestricted networks ([transfers]) waits for such a network.
         */
        fun triggerAt(cadence: WakeCadence, transfers: TransferNetwork = TransferNetwork.ANY): WakeTrigger.After = when (cadence) {
            WakeCadence.BUSY -> WakeTrigger.After(
                earliest = BUSY_EARLIEST,
                network = if (transfers == TransferNetwork.UNRESTRICTED_ONLY) WakeNetwork.UNRESTRICTED else WakeNetwork.ANY,
                cadence = cadence,
            )
            WakeCadence.IDLE -> WakeTrigger.After(earliest = IDLE_EARLIEST, network = WakeNetwork.ANY, cadence = cadence)
        }
    }
}
