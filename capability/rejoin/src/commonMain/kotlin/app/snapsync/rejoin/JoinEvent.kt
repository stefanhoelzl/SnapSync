package app.snapsync.rejoin

import app.snapsync.config.ConfigSource
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerEntry
import app.snapsync.engine.LedgerState
import app.snapsync.eventstatus.EventStatus
import app.snapsync.eventstatus.MutableEventStatusSource
import app.snapsync.gallery.GalleryResourceEnumerator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The re-join reconciliation use-case: before the background-upload producer is enabled, seed the
 * ledger with the photos already stored for the event so they are not re-uploaded — and make the
 * status correct immediately (the seed is an atomic ledger reset, so the watcher dings and the
 * projection reflects the seeded count at `Joined`, with no producer run).
 *
 * The gate is the ledger's own emptiness (the only wipe signal that never desyncs — uninstall wipes
 * the ledger with no app code running): reconcile iff an event is configured, the ledger is empty,
 * and a join has not been settled this process. An event **switch** is detected at scan time by
 * comparing the new event id to the previously-configured one ([onProvision]); the same event
 * against a non-empty ledger is a no-op. There is no persistent "joined" marker.
 *
 * Seeding runs with the producer disabled (the caller's responsibility) so there is never a
 * concurrent ledger writer; the app still constructs no `LedgerWriter` — [LedgerBackend.resetTo] is a
 * reset-family op.
 */
class JoinEvent(
    private val files: EventFilesSource,
    private val enumerator: GalleryResourceEnumerator,
    private val ledger: LedgerBackend,
    private val config: ConfigSource,
    private val status: MutableEventStatusSource,
    private val clearDiscoveryCursor: suspend () -> Unit,
) {

    // In-memory only (dies with the process, so it can never desync from a wiped ledger):
    // exactly one attempt per process unless a re-scan resets it.
    private var joinedThisSession = false
    private var failedThisSession = false

    // Serializes the session-flag/ledger mutations so concurrent gate calls cannot interleave at
    // their suspension points (the list fetch, ledger ops) and each run a redundant join. Both enable
    // triggers — the permission-grant collector's initial emission and a deeplink (re)provision — can
    // fire `ensureJoined` at launch; without this they would both pass the `joinedThisSession` check
    // before either set it, running the join twice (one losing → a spurious `JoinFailed` flap). The
    // lock makes "exactly one attempt per process" actually hold.
    private val gate = Mutex()

    /**
     * Apply a (re)provision at scan/deeplink time. [previousEventId] is the event configured *before*
     * the new id is saved. A switch (different id) resets the ledger to empty (its completions belong
     * to the old event) and clears the discovery cursor so the gate reconciles for the new event;
     * the same id leaves the ledger intact. Always clears the session flags so a re-scan retries a
     * failed join.
     */
    suspend fun onProvision(previousEventId: String?, newEventId: String) = gate.withLock {
        if (previousEventId != null && previousEventId != newEventId) {
            ledger.resetTo(emptyList())
            clearDiscoveryCursor()
        }
        joinedThisSession = false
        failedThisSession = false
    }

    /**
     * The enable gate. Returns whether the producer may be enabled now:
     * - already joined this session, or the ledger already holds rows → `true` (enable directly);
     * - a join failed this session → `false` (do not enable; the user re-scans to retry);
     * - otherwise run the join and return its outcome.
     */
    suspend fun ensureJoined(): Boolean = gate.withLock {
        val eventId = config.config.value?.eventId ?: return@withLock false
        if (joinedThisSession || ledgerHasRows()) return@withLock true
        if (failedThisSession) return@withLock false
        runJoin(eventId)
    }

    private suspend fun runJoin(eventId: String): Boolean {
        status.set(EventStatus.Joining)
        val remote = files.list(eventId).getOrElse {
            failedThisSession = true
            status.set(EventStatus.JoinFailed)
            return false
        }
        val storedFilenames = remote.mapTo(mutableSetOf()) { it.filename }
        val seeds = enumerator.enumerate()
            .filter { it.filename in storedFilenames }
            .map { resource ->
                LedgerEntry(
                    key = resource.filename,
                    assetId = resource.assetId,
                    state = LedgerState.COMPLETED,
                    attempt = 0,
                )
            }
        ledger.resetTo(seeds)
        clearDiscoveryCursor()
        joinedThisSession = true
        status.set(EventStatus.Joined)
        return true
    }

    private suspend fun ledgerHasRows(): Boolean =
        ledger.aggregates().let { it.pending + it.completed > 0 }
}
