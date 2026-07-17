package app.snapsync.upload

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
 * **Two guards, orthogonal — neither substitutes for the other** (the same split `photo-download` pins for
 * the download arm):
 * - the **active-event** guard, here, answers *"is this push for my current event?"*;
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
 * The decision lives here, in a tested capability, rather than in the composition root — which the project's
 * hard rule declares wiring-only and untested, and which is exactly where the upload arm's *previous*
 * direction gate lived when it let a download-only membership upload a member's whole camera roll
 * (capability `upload-lifecycle`).
 */
class UploadPushReceiver(
    private val activeEventId: () -> String?,
    private val pump: BackgroundUploadPump,
    private val log: Logger = Logger.withTag("UploadPushReceiver"),
) : PushReceiver {
    override suspend fun onSilentPush(eventId: String) {
        val active = activeEventId()
        if (eventId != active) {
            log.i { "silent push for $eventId ignored for upload (active event = $active)" }
            return
        }
        log.i { "silent push for active event $eventId — pumping an upload cycle" }
        pump.onSilentPush()
    }
}

/**
 * Fans one silent push out to every arm's receiver (capability `push-registration`).
 *
 * A push means "the event changed" — which is news to **both** arms: foreign photos to pull, and (since the
 * event is demonstrably live) a good moment to contribute our own. The composition root composes the arms
 * rather than either receiver knowing about the other, so `:capability:upload` and `:capability:download`
 * stay unaware of each other and each keeps its own guard.
 *
 * Each receiver is **isolated**: one failing or throwing must not rob the others of the wake. They run in
 * sequence, not concurrently — the push budget is short and the arms both touch the ledger/store, so
 * serialising is both cheaper and safer than racing them.
 */
class FanOutPushReceiver(
    private val receivers: List<PushReceiver>,
    private val log: Logger = Logger.withTag("FanOutPushReceiver"),
) : PushReceiver {
    override suspend fun onSilentPush(eventId: String) {
        for (receiver in receivers) {
            runCatching { receiver.onSilentPush(eventId) }
                .onFailure { log.w(it) { "a push receiver failed for $eventId; the others still run" } }
        }
    }
}
