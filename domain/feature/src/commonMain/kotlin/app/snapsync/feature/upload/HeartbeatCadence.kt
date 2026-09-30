package app.snapsync.feature.upload

import app.snapsync.model.CycleResult
import app.snapsync.model.WakeCadence

/**
 * What the device is, as the heartbeat's re-arm reads it after a tail — supplied by the composition, which holds the
 * membership, the grant and the extension registration (decision record `changes/timely-background-receiving`, D1, D3).
 */
data class CadenceFacts(
    /** A membership is held — the heartbeat stays pending for exactly as long. */
    val joined: Boolean,
    /** The event's range has ended: nothing new can enter it, so nothing new is left to notice by looking. */
    val ended: Boolean,
    /** The membership shares — its direction includes upload. A member who only receives has no photos to look for. */
    val shares: Boolean,
    /** The grant is full: only then does looking at the library find new photos of one's own. */
    val fullGrant: Boolean,
    /**
     * The operating system's own uploader is **confirmed** registered — it is woken by a new photo, so the app need not
     * look for one. Only the OS's own answer under a full grant counts: `extensionRegistrable` says whether registering
     * is allowed, not whether it happened.
     */
    val osUploaderConfirmed: Boolean,
)

/**
 * **The heartbeat's cadence after a tail** (capabilities `receiving-photos`, "New photos are announced by a silent wake,
 * and never only by it", and `background-upload`, "Photos upload without the app being opened"; decision record
 * `changes/timely-background-receiving`, D1) — `null` for no heartbeat at all:
 *
 * - **none** — not joined;
 * - **busy** — work remains: uploads the tail left ([leftWork]) or staged imports ([importsRemain]); or a device that
 *   notices its own new photos only by looking — a contributing member under a full grant with no library-change wake
 *   standing ([libraryWatched] on Android, the confirmed OS uploader on iOS) — until the event's end;
 * - **idle** — every other joined device: receive-only, uploads held back, a partial grant, caught up with a
 *   library-change wake standing, and every caught-up device after the end. It still looks in now and then, for
 *   others' photos and the event's close.
 *
 * Pure: the tail runner reads the facts and applies the answer.
 */
fun heartbeatCadence(
    facts: CadenceFacts,
    leftWork: Boolean,
    importsRemain: Boolean,
    contributes: Boolean,
    libraryWatched: Boolean,
): WakeCadence? {
    if (!facts.joined) return null
    if (leftWork || importsRemain) return WakeCadence.BUSY
    val looksForOwnPhotos = contributes && facts.shares && facts.fullGrant && !libraryWatched && !facts.osUploaderConfirmed
    return if (looksForOwnPhotos && !facts.ended) WakeCadence.BUSY else WakeCadence.IDLE
}

/**
 * Whether [this] tail outcome left upload work — exhaustive, so a new [CycleResult] variant is a compile error here
 * rather than a cadence nobody chose.
 */
internal val CycleResult.leftWork: Boolean
    get() = when (this) {
        CycleResult.PROCESSING, is CycleResult.Paused -> true
        CycleResult.COMPLETED, CycleResult.FAILED, CycleResult.SKIPPED -> false
    }
