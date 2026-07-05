package app.snapsync.download

import app.snapsync.push.PushReceiver
import co.touchlab.kermit.Logger

/**
 * The guarded silent-push receiver (capabilities `push-registration` + `photo-download`). On a silent
 * push carrying an `eventId`, it runs download discovery ([DownloadController.reconcile]) — **but only
 * when the pushed event is this device's ACTIVE event** (from [activeEventId]). A push for any other
 * event is a **no-op**: notably a locally-left event, whose backend membership persists (leave is
 * local-only, capability `leave-event`) and so keeps pushing this device — reconciling it would
 * silently re-pull its new photos. A push arriving with **no** event configured is likewise a no-op.
 *
 * It **suspends** until `reconcile`'s synchronous portion (the union read + download enqueue) completes,
 * so the app-shell can hold the OS background-fetch completion handler until the transfers are enqueued
 * (they then continue in the background). Non-throwing on a union failure — `reconcile` keeps last-good
 * state — so a bad network never propagates out of the receive path.
 */
class DownloadPushReceiver(
    private val activeEventId: () -> String?,
    private val controller: DownloadController,
    private val log: Logger = Logger.withTag("DownloadPushReceiver"),
) : PushReceiver {
    override suspend fun onSilentPush(eventId: String) {
        val active = activeEventId()
        if (eventId != active) {
            log.i { "silent push for $eventId ignored (active event = $active)" }
            return
        }
        log.i { "silent push for active event $eventId — reconciling" }
        controller.reconcile(eventId)
    }
}
