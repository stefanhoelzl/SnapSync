package app.snapsync.domain.wake

import app.snapsync.domain.mobiledata.PhotoNetwork
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.domain.upload.OsUploaderStanding
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * How urgently the heartbeat — the timed background wake that keeps the process tail running while the app is closed —
 * is wanted (decision record `changes/timely-background-receiving`, D1–D2).
 */
enum class HeartbeatCadence {
    /** Work remains, or a new photo can only be noticed by looking: wake again soon. */
    BUSY,

    /** Nothing is left to do: look in now and then — for others' photos and the event's close — and no more. */
    IDLE,
    ;

    /**
     * The trigger the heartbeat is requested with. It needs the network (its work uploads and reads the event) and not
     * external power, so the operating system grants windows often enough to drain a first whole-library upload. A busy
     * one for photos held to unrestricted networks waits for such a network — the transfers it would run wait for one
     * anyway, and where nothing else holds an upload for it this wake is what resumes it once the phone reaches Wi-Fi.
     * An idle one moves no photo, so it waits for any connection.
     */
    fun trigger(photos: PhotoNetwork): HeartbeatTrigger = when (this) {
        BUSY -> HeartbeatTrigger(BUSY_EARLIEST, busyNetwork(photos), this)
        IDLE -> HeartbeatTrigger(IDLE_EARLIEST, HeartbeatNetwork.ANY, this)
    }

    private fun busyNetwork(photos: PhotoNetwork): HeartbeatNetwork = when (photos) {
        PhotoNetwork.ANY -> HeartbeatNetwork.ANY
        PhotoNetwork.UNRESTRICTED_ONLY -> HeartbeatNetwork.UNRESTRICTED
    }

    companion object {
        /** Small, so a burst of re-arms coalesces into roughly one wake; the OS treats it as a lower bound. */
        val BUSY_EARLIEST: Duration = 60.seconds

        /**
         * Nothing is left to do, so the wake only looks in — for others' photos and the event's close, each checked
         * at most once an hour anyway (decision record `changes/timely-background-receiving`, D4–D5).
         */
        val IDLE_EARLIEST: Duration = 1.hours
    }
}

/** The network a heartbeat waits for. */
enum class HeartbeatNetwork {
    ANY,

    /** Neither costly nor data-restricted. A platform that cannot express it waits for [ANY]. */
    UNRESTRICTED,
}

/** No sooner than [earliest] from now, and only once [network] is there, at [cadence]. */
data class HeartbeatTrigger(val earliest: Duration, val network: HeartbeatNetwork, val cadence: HeartbeatCadence)

/** The membership as the cadence reads it. */
enum class CadenceMembership {
    NOT_JOINED,

    /** A member who only receives has no photos of their own to look for. */
    RECEIVES,

    /** Its direction includes upload. */
    SHARES,
}

/** Whether the event's range has ended: nothing new can enter it then, so nothing new is left to notice by looking. */
enum class EventRunning { RUNNING, ENDED }

/** What the wake's own uploads came to. */
enum class UploadsLeft {
    /** Work remains — or the wake was cut short, which says nothing about whether it does, so it does. */
    WORK_LEFT,

    /** The membership contributes and its uploads are caught up. */
    CAUGHT_UP,

    /** The membership contributes nothing now: no upload ran. */
    NOT_CONTRIBUTING,
}

/** Whether staged downloads still wait to be imported: work left, which keeps the heartbeat busy. */
enum class StagedImports { REMAIN, NONE }

/** Whether a library-change wake stands — a standing "wake me when a photo is added". */
enum class LibraryWatch { STANDING, NONE }

/** Whether the heartbeat is armed, and at which cadence: one is pending for exactly as long as a membership is. */
sealed interface HeartbeatVerdict {
    data object None : HeartbeatVerdict

    data class Arm(val cadence: HeartbeatCadence) : HeartbeatVerdict
}

/**
 * What the device is, as the heartbeat's re-arm reads it after a wake's own uploads (decision record
 * `changes/timely-background-receiving`, D1, D3), and **the heartbeat's cadence**:
 *
 * - **none** — not joined;
 * - **busy** — work remains: uploads the wake left, or staged imports; or a device that notices its own new photos only
 *   by looking — a sharing member under a full grant with no library-change wake standing (Android) and no confirmed OS
 *   uploader (iOS) — until the event's end;
 * - **idle** — every other joined device: receive-only, uploads held back, a partial grant, caught up with a
 *   library-change wake standing, and every caught-up device after the end. It still looks in now and then, for others'
 *   photos and the event's close.
 */
data class CadenceFacts(
    val membership: CadenceMembership,
    val event: EventRunning,
    val access: PhotoAccess,
    val osUploader: OsUploaderStanding,
    val uploads: UploadsLeft,
    val imports: StagedImports,
    val libraryWatch: LibraryWatch,
) {
    fun cadence(): HeartbeatVerdict = when (membership) {
        CadenceMembership.NOT_JOINED -> HeartbeatVerdict.None
        // A member who only receives has no photos of its own to look for: caught up, it idles.
        CadenceMembership.RECEIVES -> HeartbeatVerdict.Arm(joined(caughtUp = HeartbeatCadence.IDLE))
        CadenceMembership.SHARES -> HeartbeatVerdict.Arm(joined(caughtUp = wholeLibrary()))
    }

    private fun joined(caughtUp: HeartbeatCadence): HeartbeatCadence = when (imports) {
        StagedImports.REMAIN -> HeartbeatCadence.BUSY
        StagedImports.NONE -> afterImports(caughtUp)
    }

    private fun afterImports(caughtUp: HeartbeatCadence): HeartbeatCadence = when (uploads) {
        UploadsLeft.WORK_LEFT -> HeartbeatCadence.BUSY
        UploadsLeft.CAUGHT_UP -> caughtUp
        UploadsLeft.NOT_CONTRIBUTING -> HeartbeatCadence.IDLE
    }

    private fun wholeLibrary(): HeartbeatCadence = when (access) {
        PhotoAccess.FULL -> unwatched()
        PhotoAccess.LIMITED, PhotoAccess.NONE -> HeartbeatCadence.IDLE
    }

    private fun unwatched(): HeartbeatCadence = when (libraryWatch) {
        LibraryWatch.NONE -> unconfirmed()
        LibraryWatch.STANDING -> HeartbeatCadence.IDLE
    }

    private fun unconfirmed(): HeartbeatCadence = when (osUploader) {
        OsUploaderStanding.NOT_CONFIRMED -> running()
        OsUploaderStanding.CONFIRMED -> HeartbeatCadence.IDLE
    }

    private fun running(): HeartbeatCadence = when (event) {
        EventRunning.RUNNING -> HeartbeatCadence.BUSY
        EventRunning.ENDED -> HeartbeatCadence.IDLE
    }
}
