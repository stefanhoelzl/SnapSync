package app.snapsync.feature.membership

/**
 * Entering a **new** membership (capabilities `join-event`, `upload-state-reconciliation`): what a first join
 * or a switch does before the new config is saved. A re-provision of the joined event is not an entry and
 * never reaches here ([SwitchDecision.Stay]).
 *
 * On a switch the previous membership is left first, as a leave leaves it: its uploads **stop** before
 * anything else, so no mechanism starts new work against a ledger about to be replaced, and then the
 * best-effort backend leave fires. Then — for a switch and a first join alike — the upload ledger becomes
 * the new membership's share set ([ShareSetLoad]).
 *
 * The order is the whole of this class, which is why it is a class rather than three lambdas in the flow:
 * the flow grammar allows one call per branch of the transition (spec `architecture-diagrams`), and the
 * ordering is a rule worth a test of its own.
 */
class MembershipEntry(
    /** Stop the previous membership's uploads (the upload arm's leave verb). */
    private val stopUploads: suspend () -> Unit,
    /** The best-effort backend leave of the previous event. */
    private val notifyLeave: suspend (eventId: String) -> Unit,
    /** Make the upload ledger the new membership's share set. */
    private val loadShareSet: suspend () -> Unit,
) {
    /** Enter a new membership, leaving [previousEventId] first when this is a switch (`null` for a first join). */
    suspend fun enter(previousEventId: String?) {
        if (previousEventId != null) {
            stopUploads()
            notifyLeave(previousEventId)
        }
        loadShareSet()
    }
}
