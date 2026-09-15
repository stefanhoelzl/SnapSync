package app.snapsync.ports

import app.snapsync.model.LedgerAggregates
import app.snapsync.model.LedgerEntry
import app.snapsync.model.PendingResource
import kotlinx.coroutines.flow.Flow

/**
 * The ledger's storage seam — a dumb row store. Backends store the fields of an applied write verbatim: no
 * interpretation, no clocks of their own. The only precedence a backend applies is the one each guarded
 * write names ([recordUnlessSettled], [markTerminal]), and each enforces it inside its
 * own statement; the reset family applies none. Record semantics live above, in `LedgerWriter`, written once
 * for every backend. [changes] dings after every write that changed the store — no payload, the only promise
 * is "re-read the truth", so conflation and missed signals are harmless by construction. The ding
 * is **in-process only**: the ledger is the extension's private upload memory with no cross-process
 * watcher, so backends post no cross-process (e.g. Darwin) notification.
 *
 * There is deliberately **no** unconditional per-row upsert. `put` was removed once the record path became
 * guarded: with no production caller left it could only be an unguarded door for the next write. Tests seed
 * a store through [recordUnlessSettled] or [resetTo], like production does.
 */
interface LedgerStore : TransferRecord {
    val changes: Flow<Unit>

    /**
     * Absence: null means "no such row", and ONLY that — a backend that cannot read throws rather
     * than answering empty, so this seam never has to encode "could not tell". That is what lets a
     * caller treat null as a fact about the ledger instead of a fact about the storage.
     */
    suspend fun get(key: String): LedgerEntry?

    /**
     * Upsert one complete row — **unless the row already there is in a done state**
     * ([app.snapsync.model.DONE_STATES]); answers whether it applied.
     *
     * The guard is the operation's purpose, and it lives in the storage statement, not in a caller's
     * preceding read: a read-then-write is not atomic against a second writer, and a late record over a
     * settled row — a stale `FAILED`, a duplicate `REQUESTED` — would demand a job for bytes the backend
     * already holds. Transitions between non-done states still apply.
     *
     * Dings [changes] only when it applied. `false` means the row was settled, which is a different fact from
     * "recorded" and SHALL NOT be discarded silently (`module-architecture`, "Absence is never silent").
     */
    suspend fun recordUnlessSettled(entry: LedgerEntry): Boolean

    suspend fun aggregates(): LedgerAggregates

    /**
     * The non-settled rows (the backlog) as [PendingResource]s. Returns exactly the rows whose state is
     * not in [app.snapsync.model.DONE_STATES], interpreting nothing else — the backend stays a dumb row
     * store, and *which* states are settled is decided once, in `model/`, not per query.
     */
    suspend fun pendingResources(): List<PendingResource>

    /**
     * The rows that **need an upload job**, in a stable key order — the upload cycle's source of work
     * (capability `sync-ledger`).
     *
     * Returns exactly the rows whose state is in [app.snapsync.model.NEEDS_JOB_STATES], interpreting
     * nothing else: *which* states need a job is decided once, in `model/`, not per query. That set spans
     * `DISCOVERED` and `FAILED`, which are the same fact to a producer — a key with no live job and no
     * bytes on the backend — differing only in whether an attempt was already made.
     *
     * **Unbounded, deliberately.** A cycle does bound its work — a first walk on a large library records a
     * row per outstanding resource, and enqueuing all of them would stage every one to disk — but it
     * bounds what it **resolves**, never what it reads, because a row needing a job is not yet the
     * admitted set (capability `photo-selection-policy`). A bound here would starve: rows come back in a
     * stable key order, so rows the membership's current policy excludes, sorting ahead of admitted ones,
     * would fill the slice on every cycle and the admitted work further down would never be reached. The
     * scan is local and indexed; the platform round-trip the bound protects is the caller's to make.
     *
     * Absent rows are excluded: the asset has left the library, so there is nothing to upload from.
     */
    suspend fun rowsNeedingJob(): List<LedgerEntry>

    /**
     * The `REQUESTED` keys — the candidates for the app-driven tier's stranded reconciliation
     * (`ios-url-session-upload`).
     *
     * Deliberately narrower than [pendingResources], which that tier used to read for this and which
     * returns the whole non-settled backlog. A `FAILED` row has already been adjudicated; re-surfacing it
     * every cycle re-writes the row, signals a change, and reports a loss that did not happen — a device
     * log shows one key "stranded" twelve times inside a single process, seven within sixteen seconds.
     */
    suspend fun requestedKeys(): Set<String>

    /**
     * The rows the **device manifest** projects from (capability `device-manifest`): every row this
     * device has not marked absent, whatever its upload state.
     *
     * Deliberately **not state-scoped**, and deliberately carrying no state adjective in its name. The
     * manifest declares what this member *intends to provide*, and that does not depend on how far a
     * resource's bytes have got — so a `DISCOVERED` row and a `COMPLETED` one are equally listed. This
     * read used to return only settled rows, and the stale word "completed" in its name outlived the
     * decision behind it: `api-endpoints` came to describe a manifest that declares intent while
     * `device-manifest` still required the completed projection.
     *
     * It filters on a fact about the ROW (`absent` — the asset left the library, so this device no longer
     * shares it) and on nothing else. **Admission is the policy's**, applied by the projection: the
     * capture-date bounds, and with them the exclusion of a row whose `creationDate` is still bare, whose
     * empty value sorts before every real cutoff. Restating that here would be a second copy of an
     * admission rule (capability `photo-selection-policy`).
     */
    suspend fun manifestRows(): List<LedgerEntry>

    /**
     * Fill the manifest detail of one already-recorded row **without touching its state or attempt**,
     * and only while the row is still bare — so re-running is free and can never clobber a good value.
     *
     * The sweep for the two ways a row rests bare: it predates the 5.sqm migration, or the re-join
     * reconcile seeded it from a stored-file listing (filenames carry no capture date). A writer-family
     * operation like the prunes and [backfillEventId]: only the single writer's cycle runs it.
     */
    suspend fun backfillManifestDetail(entry: LedgerEntry)

    /**
     * Delete every row — a deliberate reset (the app re-provisioning config), not a sync write.
     * Dings [changes] so watchers re-read the now-empty truth.
     */
    suspend fun clear()

    /**
     * Delete every `REQUESTED` row, leaving `COMPLETED` and `FAILED` rows untouched — an **app-side
     * reset-family** op (alongside [clear]), not a writer-only prune. The recovery for jobs the OS
     * wiped when the extension was disabled: those resources stay `REQUESTED`, the engine never
     * re-issues a `REQUESTED` key, and no API surfaces the vanished job — so clearing `REQUESTED` is
     * what lets the next discovery re-create them. Clearing **all** `REQUESTED` is correct because a
     * disable wipes **all** in-flight jobs at once. Dings [changes] once, like [clear].
     */
    suspend fun clearRequested()

    /**
     * Atomically replace the entire store with [entries] (delete-all then insert-all in one
     * transaction): either all prior rows go and all [entries] land, or — on failure — the store is
     * left exactly as it was (no partial baseline is ever observable). Entries are stored verbatim
     * (the caller supplies `state`; no clock stamping here). Dings [changes]
     * **once** on success. It applies no precedence — a settled row is replaced like any other. This is a
     * reset-family op (alongside [clear]) — the app-side
     * join seed uses it; it is **not** a per-key record, so it does not breach the single-record-writer
     * invariant.
     */
    suspend fun resetTo(entries: List<LedgerEntry>)

    /**
     * Mark every row whose [LedgerEntry.assetId] equals [assetId] as [LedgerEntry.absent] — the asset has
     * left this device's library. The rows are **kept**: what they record (these bytes are on the
     * backend) is still true, and keeping them is what stops a restored asset re-uploading. The backend
     * matches by equality and never interprets the value — `assetId` is a second opaque grouping field
     * (it does not know what an "asset" means). Idempotent. Dings [changes].
     *
     * There is deliberately **no** `retainAssets`, and no delete-by-asset at all. Retention used to prune
     * every row outside a supplied keep-set, and the cycle supplied the **policy-admitted** set — so
     * raising a capture cutoff discarded the `COMPLETED` rows of photos that were still in the library
     * and still uploaded. Those rows are exactly what suppresses re-upload, so the narrowing became
     * irreversible, and a membership turned download-only would have lost the event's rows entirely,
     * defeating the drain that exists so re-enabling re-uploads nothing (capability
     * `reconfigure-membership`). A scope change belongs to the manifest projection (capability
     * `device-manifest`), never to this record.
     */
    suspend fun markAbsent(assetId: String)

    /**
     * Clear [LedgerEntry.absent] on every row whose [LedgerEntry.assetId] is among [assetIds] — the inverse of
     * [markAbsent], for assets a walk has seen in the library again.
     *
     * **Whatever the row's state.** A settled row is exactly the one no record write reaches again — the engine
     * writes nothing for an already-uploaded resource — so without this a restored photo would stay out of the
     * device manifest for good. Every other column is preserved.
     *
     * Runs every cycle over every asset the walk saw, so it SHALL write nothing when none of them is marked,
     * and dings [changes] only when it cleared a mark.
     */
    suspend fun markPresent(assetIds: Collection<String>)

    /**
     * Rewrite the [LedgerEntry.eventId] of every row whose value is the pre-provenance sentinel
     * `""` to [eventId], leaving every other field — and every row already carrying a real
     * eventId — untouched. The backend matches the sentinel by equality and interprets nothing.
     * Idempotent (a sweep that matches no rows is a no-op) and cheap, so the writer runs it once
     * per cycle entry. Dings [changes] once, like the other bulk operations — provenance is
     * invisible to today's watchers, but the ding keeps the level-trigger contract uniform.
     */
    suspend fun backfillEventId(eventId: String)
}
