package app.snapsync.feature.upload

import app.snapsync.model.LedgerEntry
import app.snapsync.model.Resource
import app.snapsync.model.toLedgerRow
import app.snapsync.model.LedgerState
import app.snapsync.ports.LedgerStore

/**
 * The ledger's single writer (one per platform, hosted with the engine), carrying the engine's
 * per-key read ([entry]). Each record operation upserts a complete, self-contained entry in one
 * backend [LedgerStore.put] — no operation depends on a prior read, so duplicate records converge
 * per key on state and attempt. The writer keeps no clock; the engine and backends are all
 * clock-free and store verbatim. Only the composition root that owns the engine ever constructs it.
 * Aggregates and change signals are deliberately absent from this per-key face; the extension's own
 * cycle reads them via [LedgerStore] directly.
 */
class LedgerWriter(
    private val backend: LedgerStore,
) {

    suspend fun entry(key: String): LedgerEntry? = backend.get(key)

    /**
     * Record that the walk found [resource] and the policy admitted it — **only when the key has no row
     * yet**; answers whether it applied.
     *
     * The guard is the operation's purpose, and it is why this is not just `record(…, DISCOVERED, …)`.
     * A key the engine answers `Work` for is absent, `DISCOVERED`, or `FAILED`, and the last of those
     * already needs a job: writing over it would reset an attempt count that a retry chain is counting
     * on, to say something the row already says.
     *
     * The read-then-write is safe here where it would not be on a terminal transition: this cycle is the
     * ledger's only writer of non-terminal states and the pump is single-flight, and the one writer that
     * does not take that lock — the platform's delegate, through `markTerminal` — is guarded on
     * `REQUESTED`, so it cannot touch a key that has no row.
     */
    suspend fun recordDiscovered(resource: Resource, eventId: String): Boolean {
        if (backend.get(resource.filename) != null) return false
        record(resource, LedgerState.DISCOVERED, attempt = 0, eventId)
        return true
    }

    suspend fun recordRequested(resource: Resource, attempt: Int, eventId: String) =
        record(resource, LedgerState.REQUESTED, attempt, eventId)

    suspend fun recordCompleted(resource: Resource, attempt: Int, eventId: String) =
        record(resource, LedgerState.COMPLETED, attempt, eventId)

    suspend fun recordFailed(resource: Resource, attempt: Int, eventId: String) =
        record(resource, LedgerState.FAILED, attempt, eventId)

    /**
     * Record that [assetId] has left the library — a sync write by the single writer (distinct from the
     * app-side [LedgerStore.clear] reset). At the writer layer it consults no engine state; it just
     * marks. The rows survive, so a restored asset re-uploads nothing.
     */
    suspend fun markAbsent(assetId: String) = backend.markAbsent(assetId)

    /**
     * Sweep every pre-provenance row (`eventId = ""` — recorded before the ledger carried the
     * column, or by a staged-revert build) to [eventId]. A writer-family operation like the
     * prunes: only the single-writer's cycle runs it, once per entry, idempotently.
     */
    suspend fun backfillEventId(eventId: String) = backend.backfillEventId(eventId)

    /**
     * Fill an already-recorded row's manifest detail from the freshly discovered [resource]
     * (capability `sync-ledger`). A no-op unless the row is still bare.
     *
     * This is what makes the ledger-backed manifest survive a re-join: the reconcile seeds
     * `COMPLETED` rows from a filename listing, the engine then answers `AlreadyUploaded` for each
     * and writes nothing, so without this the seeded rows would never learn their capture date and
     * the member's photos would silently drop out of the event union.
     */
    suspend fun backfillManifestDetail(resource: Resource, eventId: String) =
        backend.backfillManifestDetail(resource.toLedgerRow(LedgerState.COMPLETED, attempt = 0, eventId))

    /** The COMPLETED rows the device manifest projects from. */
    suspend fun completedManifestRows(): List<LedgerEntry> = backend.completedManifestRows()

    /** The rows the platform recorded `UPLOADED` — what the cycle's promotion pass consumes. */
    suspend fun uploadedRows(): List<LedgerEntry> = backend.uploadedRows()

    /**
     * At most [limit] rows that need an upload job — the cycle's **source of work** (capability
     * `sync-ledger`), spanning `DISCOVERED` and `FAILED`.
     *
     * A read on the writer's face, like [completedManifestRows] and [uploadedRows] beside it, because
     * the cycle that consumes it is the single writer and asks through this one seam.
     */
    suspend fun rowsNeedingJob(limit: Int): List<LedgerEntry> = backend.rowsNeedingJob(limit)

    /**
     * Promote one `UPLOADED` row to `COMPLETED` — the cycle's half of the two-phase completion, run once
     * the event-album placement and the notify have been dealt with.
     *
     * Delegates to the store's guarded update rather than writing the row back: a re-statement would have
     * to name every column, and would silently drop whichever one it had not been taught about. This is
     * the write that makes a row eligible for the device manifest, so blanking its detail here would drop
     * the photo out of the event union at the exact moment it became listable.
     */
    suspend fun promote(key: String): Boolean = backend.promoteUploaded(key)

    /**
     * Record a state transition, carrying the manifest detail off the resource that caused it — and
     * **never erasing** detail the row already holds.
     *
     * The preservation is load-bearing, not defensive. A terminal job comes back from the platform as a
     * key, and the cycle rebuilds its `Resource` from that key alone (`UploadCycle.reconstruct`) with
     * empty metadata, because completion needs nothing else. So the COMPLETED write — the only write
     * that ever produces a row the manifest projects from — carries no capture date. Overwriting with it
     * would blank every row at the exact moment it became eligible for the manifest, and the device's
     * photos would vanish from the event union while its bytes sat in storage.
     *
     * The detail is a property of the **resource**, not of the transition: it was written when the row
     * was first recorded from a real discovered resource, and a later state change has nothing new to
     * say about it.
     */
    private suspend fun record(resource: Resource, state: LedgerState, attempt: Int, eventId: String) {
        val row = resource.toLedgerRow(state, attempt, eventId)
        val prior = if (row.needsManifestDetail) backend.get(row.key) else null
        backend.put(
            if (prior == null || prior.needsManifestDetail) {
                row
            } else {
                LedgerEntry(
                    key = row.key,
                    assetId = row.assetId,
                    state = state,
                    attempt = attempt,
                    eventId = eventId,
                    creationDate = prior.creationDate,
                    role = prior.role,
                    contentType = prior.contentType,
                    originalFilename = prior.originalFilename,
                )
            },
        )
    }
}
