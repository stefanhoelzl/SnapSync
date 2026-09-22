package app.snapsync.feature.upload

import app.snapsync.model.LedgerEntry
import app.snapsync.model.Resource
import app.snapsync.model.toLedgerRow
import app.snapsync.model.LedgerState
import app.snapsync.ports.LedgerStore
import co.touchlab.kermit.Logger

/**
 * The ledger's single writer (one per platform, hosted with the engine), carrying the engine's
 * per-key read ([entry]). Each record operation upserts a complete, self-contained entry through the
 * backend's guarded [LedgerStore.recordUnlessSettled] — which never overwrites a settled row, and whose
 * guard does not depend on any read made here — so duplicate records converge per key on state. The writer
 * keeps no clock; the engine and backends are all clock-free and store verbatim. Only the composition root
 * that owns the engine ever constructs it. Aggregates and change signals are deliberately absent from this
 * per-key face; the extension's own cycle reads them via [LedgerStore] directly.
 */
class LedgerWriter(
    private val backend: LedgerStore,
) {

    private val log = Logger.withTag("LedgerWriter")

    suspend fun entry(key: String): LedgerEntry? = backend.get(key)

    /**
     * Record that the walk found [resources] and the policy admitted them — **only for keys with no row
     * yet**, all in one transaction; answers how many applied.
     *
     * The guard is the operation's purpose, and it is why this is not just `record(…, DISCOVERED, …)`.
     * A key with a row already has a state the walk has no business replacing: a `DISCOVERED` row already
     * says what this write would say, and a `REQUESTED` one records a live job that a rewrite to
     * `DISCOVERED` would orphan.
     *
     * The read-then-write is safe here where it would not be on a terminal transition: this cycle is the
     * ledger's only writer of non-terminal states and the pump is single-flight, and the one writer that
     * does not take that lock — the platform's delegate, through `markTerminal` — is guarded on
     * `REQUESTED`, so it cannot touch a key that has no row.
     */
    suspend fun recordDiscovered(resources: Collection<Resource>): Int {
        val fresh = resources.filter { backend.get(it.filename) == null }
        // One batch, so the walk's discoveries land whole or not at all: a walk skips an asset whose rows
        // all exist, so one role recorded without its sibling would leave the sibling unrecorded for good.
        return backend.recordAllUnlessSettled(fresh.map { it.toLedgerRow(LedgerState.DISCOVERED) })
    }

    /**
     * [destinationPath] rides THIS write and no other: it is the one transition that means "an upload for
     * this row now exists at the platform", so it is the moment the address becomes true. Recording it
     * separately would open a window in which a job exists whose destination the ledger does not know.
     */
    suspend fun recordRequested(resource: Resource, destinationPath: String? = null) =
        record(resource, LedgerState.REQUESTED, destinationPath)

    /**
     * Record that an upload failed: the row returns to `DISCOVERED`, so the ledger's work read offers it again.
     * Named for why it is called; the state is what a failure means to the ledger (there is no `FAILED` state —
     * decision record `changes/shrink-the-ledger-row`, D3).
     */
    suspend fun recordFailed(resource: Resource) = record(resource, LedgerState.DISCOVERED)

    /**
     * Delete exactly the rows keyed by [keys] — the cycle's one row deletion (capability `sync-ledger`,
     * "Deletion is a presence diff over an authoritative walk"). A sync write by the single writer, and
     * key-scoped on purpose: the caller holds evidence about individual rows, never about every row an
     * asset has.
     */
    suspend fun deleteKeys(keys: Collection<String>) = backend.deleteKeys(keys)

    /**
     * Fill an already-recorded row's manifest detail from the freshly discovered [resource]
     * (capability `sync-ledger`). A no-op unless the row is still bare.
     *
     * This is what makes the ledger-backed manifest survive a re-join: the reconcile seeds
     * `COMPLETED` rows from a filename listing, the engine then answers `AlreadyUploaded` for each
     * and writes nothing, so without this the seeded rows would never learn their capture date and
     * the member's photos would silently drop out of the event union.
     */
    suspend fun backfillManifestDetail(resource: Resource) =
        backend.backfillManifestDetail(resource.toLedgerRow(LedgerState.COMPLETED))

    /** The rows the device manifest projects from — every row, whatever its state. */
    suspend fun manifestRows(): List<LedgerEntry> = backend.manifestRows()

    /**
     * Every row that needs an upload job — the cycle's **source of work** (capability `sync-ledger`): the
     * `DISCOVERED` rows, never attempted or returned there by a failure, in a stable key order.
     *
     * Unbounded: the caller admits these rows against the membership's current policy and bounds what it
     * **resolves**, because a bound on the read would starve admitted work behind excluded rows (see the
     * port's KDoc).
     *
     * A read on the writer's face, like [manifestRows] beside it, because the cycle that consumes it is
     * the single writer and asks through this one seam.
     */
    suspend fun rowsNeedingJob(): List<LedgerEntry> = backend.rowsNeedingJob()

    /**
     * Record a state transition, carrying the manifest detail off the resource that caused it — and
     * **never erasing** detail the row already holds.
     *
     * The preservation is load-bearing, not defensive. A terminal job comes back from the platform as a
     * key, and the cycle rebuilds its `Resource` from that key alone (`UploadCycle.reconstruct`) with
     * empty metadata, because adjudicating a failure needs nothing else. So the failure's write — and the
     * `REQUESTED` write of the job re-created from it — carries no capture date. Overwriting with it would
     * blank the row's manifest detail, and the device manifest projects every row whatever its state, so
     * the photo would drop out of the event union while its upload was still being retried.
     *
     * The detail is a property of the **resource**, not of the transition: it was written when the row
     * was first recorded from a real discovered resource, and a later state change has nothing new to
     * say about it.
     *
     * The `prior` read serves that preservation and nothing else. It is NOT the settled-row guard: that lives
     * in the backend's statement, because a read here followed by a write is not atomic against a second
     * writer. A declined write is logged rather than dropped — with one writer it means a rare late record
     * reached a finished row (`module-architecture`, "Absence is never silent").
     */
    private suspend fun record(
        resource: Resource,
        state: LedgerState,
        destinationPath: String? = null,
    ): Boolean {
        val row = resource.toLedgerRow(state, destinationPath)
        val prior = if (row.needsManifestDetail) backend.get(row.key) else null
        val applied = backend.recordUnlessSettled(
            if (prior == null || prior.needsManifestDetail) {
                row
            } else {
                LedgerEntry(
                    key = row.key,
                    assetId = row.assetId,
                    state = state,
                    creationDate = prior.creationDate,
                    role = prior.role,
                    contentType = prior.contentType,
                    originalFilename = prior.originalFilename,
                    // Preserved on the same terms as the manifest detail: a later transition has nothing
                    // new to say about where the upload was addressed, and blanking it would strand the
                    // row exactly when the platform hands its job back.
                    destinationPath = destinationPath ?: prior.destinationPath,
                )
            },
        )
        if (!applied) log.w { "declined $state over a settled row key=${row.key}" }
        return applied
    }
}
