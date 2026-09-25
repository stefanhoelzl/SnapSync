package app.snapsync.feature.membership

import app.snapsync.model.EventConfig

/**
 * Entering a **new** membership (capabilities `join-event`, `photo-sharing`, `background-upload`):
 * what a first join or a switch does, in order. A re-provision of the joined event is not an entry and never
 * reaches here ([SwitchDecision.Stay]) — which is how a re-scan of the joined event does nothing to uploads.
 *
 * 1. On a switch the previous membership is left first, as a leave leaves it: its uploads **stop** (the
 *    extension deregistered, the app's transfers cancelled) before anything else, so no uploader starts new
 *    work against a ledger about to be replaced, and then the best-effort backend leave fires.
 * 2. The upload ledger becomes the new membership's share set ([ShareSetLoad]) — BEFORE the save, so no cycle
 *    ever sees the new membership over the previous one's ledger.
 * 3. The whole config is saved.
 * 4. The uploads are started for the membership just saved — the join transition: the extension registration
 *    forced where the OS allows it, the app armed where access is usable. After the save, so a registered
 *    extension never reads the previous membership's config over the new ledger.
 *
 * The order is the whole of this class, which is why it is a class rather than lambdas in the flow: the flow
 * grammar allows one call per branch of the transition (`docs/architecture.md`), and the ordering is a
 * rule worth a test of its own. Decision record: `changes/both-uploaders-active` (D5).
 */
class MembershipEntry(
    /** Stop the previous membership's uploads (the upload arm's leave verb). */
    private val stopUploads: suspend () -> Unit,
    /** The best-effort backend leave of the previous event. */
    private val notifyLeave: suspend (eventId: String) -> Unit,
    /** Make the upload ledger the new membership's share set. */
    private val loadShareSet: suspend () -> Unit,
    /** Persist the whole config (a port touch). */
    private val saveConfig: suspend (EventConfig) -> Unit,
    /** Start the uploads for the membership just saved (the upload arm's join verb). */
    private val startUploads: suspend () -> Unit,
) {
    /** Enter [cfg]'s membership, leaving [previousEventId] first when this is a switch (`null` for a first join). */
    suspend fun enter(previousEventId: String?, cfg: EventConfig) {
        if (previousEventId != null) {
            stopUploads()
            notifyLeave(previousEventId)
        }
        loadShareSet()
        saveConfig(cfg)
        startUploads()
    }
}
