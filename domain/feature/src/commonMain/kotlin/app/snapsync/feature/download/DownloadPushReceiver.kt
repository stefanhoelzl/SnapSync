package app.snapsync.feature.download

import app.snapsync.domain.event.EventId
import app.snapsync.model.MembershipRead
import app.snapsync.model.UnionTrigger
import app.snapsync.model.pushEventId
import app.snapsync.model.pushSeq
import app.snapsync.services.config.ConfigService
import co.touchlab.kermit.Logger

/**
 * The guarded silent-push receiver. On a silent
 * push carrying an `eventId`, it runs download discovery ([DownloadController.reconcile]) — **but only
 * when the pushed event is this device's ACTIVE event** (from [activeEventId]). A push for any other
 * event is a **no-op**: notably a locally-left event, whose backend membership persists (leave is
 * local-only) and so keeps pushing this device — reconciling it would
 * silently re-pull its new photos. A push arriving with **no** event configured is likewise a no-op.
 *
 * The push names the union position it announces ([onSilentPush]'s `announced`, decision record
 * `changes/incremental-union`, D6): the reconcile reads only what was gained since this device's stored position, and
 * nothing at all when that position is already at or past the announced one — so a burst of pushes after one read
 * costs no further reads.
 *
 * It **suspends** until `reconcile`'s synchronous portion (the union read + download enqueue) completes,
 * so the app-shell can hold the OS background-fetch completion handler until the transfers are enqueued
 * (they then continue in the background). Non-throwing on a union failure — `reconcile` keeps last-good
 * state — so a bad network never propagates out of the receive path.
 */
class DownloadPushReceiver(
    /** The membership: the active event id is read fresh at every push. */
    private val configSource: ConfigService,
    private val controller: DownloadController,
    private val log: Logger = Logger.withTag("DownloadPushReceiver"),
) {
    /**
     * A silent push arrived with [payload], whole: a payload with no usable event id runs nothing — the codec in `model/`
     * is the one place that knows its shape.
     */
    suspend fun onSilentPush(payload: Map<Any?, *>) {
        val eventId = pushEventId(payload) ?: return log.i { "silent push carried no eventId — nothing to receive" }
        onSilentPush(eventId, pushSeq(payload))
    }

    suspend fun onSilentPush(eventId: EventId, announced: Long? = null) {
        val active = when (val membership = configSource.membership) {
            is MembershipRead.Member -> membership.config.eventId
            MembershipRead.NotMember -> null
            // "No event configured" and "could not read which" are different facts, and only one of them is a
            // reason to look at the device's lock state: deferred, and the next push or wake reads it again.
            MembershipRead.Unreadable -> {
                log.w { "silent push for $eventId deferred (download) — the membership is unreadable right now" }
                return
            }
        }
        if (eventId != active) {
            log.i { "silent push for $eventId ignored (active event = $active)" }
            return
        }
        log.i { "silent push for active event $eventId — reconciling" }
        controller.reconcile(eventId, UnionTrigger.PUSH, announced)
    }
}
