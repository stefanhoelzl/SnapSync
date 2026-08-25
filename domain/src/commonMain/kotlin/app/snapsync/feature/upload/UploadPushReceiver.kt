package app.snapsync.feature.upload

import app.snapsync.model.PermissionStatus
import app.snapsync.ports.PushReceiver
import co.touchlab.kermit.Logger

/**
 * The guarded silent-push receiver for the **upload** arm (capabilities `push-registration` +
 * `ios-url-session-upload`) — the mirror of `DownloadPushReceiver`. On a silent push carrying an `eventId`,
 * it drives an upload cycle ([pump]) — **but only when the pushed event is this device's ACTIVE event**
 * (from [activeEventId]).
 *
 * **Why a push drives uploads at all.** The `BGProcessingTask` heartbeat is scheduled at the OS's
 * discretion and is routinely deferred far past its `earliestBeginDate` — least dependable exactly when an
 * event is live and photos are being taken. A silent push is the reliable wake, and it *clusters* on live
 * events by construction: it is emitted when another member's device drains a cycle that completed an upload
 * (capability `upload-completion-notify`). A peer's photo arriving is this device's best signal that it
 * probably has photos of its own to contribute.
 *
 * **Three guards, orthogonal — none substitutes for another** (the same split `photo-download` pins for
 * the download arm):
 * - the **active-event** guard, here, answers *"is this push for my current event?"*;
 * - the **read-discipline** guard, also here, answers *"may an autonomous trigger read the library?"*;
 * - the **direction** gate, in `UploadCycle`, answers *"should this device ever upload here?"*
 *
 * So a push for the active event on a download-only membership passes *this* guard and still uploads
 * nothing: the cycle returns [CycleResult.SKIPPED] and the pump schedules nothing. That is the correct
 * layering — this receiver does not know about direction, and must not learn.
 *
 * A push for any other event is a **no-op**: notably a locally-left event, whose backend membership persists
 * (leave is local-only, capability `leave-event`) and which therefore keeps pushing this device. A push
 * arriving with **no** event configured is likewise a no-op.
 *
 * **The read-discipline guard moved here** from the silent-push fan-out (capability
 * `limited-photo-access`, "No autonomous library reads under a limited grant", which names *"the upload
 * half of the silent-push fan-out"* among exactly three triggers that must skip their `PHAsset` work under
 * a partial grant, and fixes reads at two moments a push is not one of). At the fan-out it was an
 * **invoker-gate**, and `upload-lifecycle` has already ruled on that shape once: its soundness depends on
 * the fan-out's enumeration of who might read, which a new mechanism or a new trigger invalidates in
 * silence. It is `GRANTED` **exactly**, not "usable access": a partial grant is precisely the case it
 * exists to refuse.
 *
 * The decision lives here, in a tested feature, rather than in the composition root — which the project's
 * hard rule declares wiring-only and untested, and which is exactly where the upload arm's *previous*
 * direction gate lived when it let a download-only membership upload a member's whole camera roll
 * (capability `upload-lifecycle`). The cross-arm fan-out that used to sit beside this class
 * (`FanOutPushReceiver`) is now the `flow/SilentPush` flow (migration step 8): a push fanned to each arm's
 * receiver is the silent-push trigger's coordination, not a receiver's own job.
 */
class UploadPushReceiver(
    private val activeEventId: () -> String?,
    private val pump: BackgroundUploadPump,
    /** Current photo access. Read fresh: a grant can change between pushes. */
    private val permission: () -> PermissionStatus,
    private val log: Logger = Logger.withTag("UploadPushReceiver"),
) : PushReceiver {
    override suspend fun onSilentPush(eventId: String) {
        val active = activeEventId()
        if (eventId != active) {
            log.i { "silent push for $eventId ignored for upload (active event = $active)" }
            return
        }
        val access = permission()
        if (access != PermissionStatus.GRANTED) {
            // Logged rather than dropped: "the push arrived and this arm declined it" and "no push
            // arrived" are different facts, and only one of them is a reason to look at the grant.
            log.i { "silent push for $eventId not pumped — photo access is $access, not a full grant" }
            return
        }
        log.i { "silent push for active event $eventId — pumping an upload cycle" }
        pump.onSilentPush()
    }
}
