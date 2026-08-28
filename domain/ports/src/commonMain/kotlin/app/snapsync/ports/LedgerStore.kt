package app.snapsync.ports

import app.snapsync.model.LedgerAggregates
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.PendingResource
import kotlinx.coroutines.flow.Flow

/**
 * The ledger's storage seam — a dumb row store. Backends store entries verbatim: no
 * interpretation, no precedence, no clocks of their own, last write wins. [put] is a single-row
 * upsert and the unit of atomicity; record semantics live above, in [LedgerWriter], written once
 * for every backend. [changes] dings after every successful put — no payload, the only promise
 * is "re-read the truth", so conflation and missed signals are harmless by construction. The ding
 * is **in-process only**: the ledger is the extension's private upload memory with no cross-process
 * watcher, so backends post no cross-process (e.g. Darwin) notification.
 */
interface LedgerStore {
    val changes: Flow<Unit>

    /**
     * Absence: null means "no such row", and ONLY that — a backend that cannot read throws rather
     * than answering empty, so this seam never has to encode "could not tell". That is what lets a
     * caller treat null as a fact about the ledger instead of a fact about the storage.
     */
    suspend fun get(key: String): LedgerEntry?
    suspend fun put(entry: LedgerEntry)
    suspend fun aggregates(): LedgerAggregates

    /**
     * The non-settled rows (the backlog) as [PendingResource]s. Returns exactly the rows whose state is
     * not in [app.snapsync.model.DONE_STATES], interpreting nothing else — the backend stays a dumb row
     * store, and *which* states are settled is decided once, in `model/`, not per query.
     */
    suspend fun pendingResources(): List<PendingResource>

    /**
     * Flip one row's state, but **only while that row is still `REQUESTED`**; answers whether it applied.
     *
     * The guard is the operation's purpose. Two writers reach a row holding no shared lock — a platform
     * callback recording that an upload terminated, on the platform's own queue, and the upload cycle's
     * stranded pass on the composition lane — so a read-then-write pair is not atomic against the one that
     * does not take the lock. Putting the condition in the write is what makes a fact recorded underneath
     * a stale read impossible to clobber. (`photo-download` reached the same conclusion for the same
     * reason: *"the guard SHALL live in the store's write rather than in a caller's preceding read"*.)
     *
     * Every other column is preserved by the backend rather than re-supplied here: the caller is a delegate
     * that holds only the key and cannot re-state `assetId`, `attempt`, `eventId` or the manifest detail.
     *
     * **Non-suspending**, because its caller cannot suspend — an ObjC completion block is not a coroutine —
     * and because the write must land *before* that callback returns. After it returns the process's
     * continued runtime is not guaranteed, so a scheduled write races the system's willingness to keep
     * running us.
     *
     * `false` means the row was not `REQUESTED` — already terminal, or pruned. That is a different fact
     * from "recorded" and callers SHALL NOT discard it silently (`module-architecture`, "Absence is never
     * silent").
     *
     * This is a **record** operation on a non-writer surface, which the reader/writer split otherwise
     * forbids. It is deliberate and narrow: the party the platform tells is inside the single
     * record-writing process, and the invariant is that exactly one *process* records. See `sync-ledger`,
     * "Reader and writer capability split". Do not add a second record operation here on this argument.
     */
    fun markTerminal(key: String, state: LedgerState): Boolean

    /**
     * The rows whose bytes are stored but whose completion work has not run — what the cycle's promotion
     * pass consumes (capability `upload-completion-notify`). Whole entries: the pass needs `assetId` for
     * the event-album placement, and the manifest detail rides along so promoting a row cannot blank it.
     */
    suspend fun uploadedRows(): List<LedgerEntry>

    /**
     * Settle one `UPLOADED` row once the work a completion triggers has run; answers whether it applied.
     *
     * Guarded like [markTerminal], and preserving every other column the same way — by being an update of
     * one column rather than a re-statement of the row. That is not fastidiousness: the row carries
     * provenance, an attempt, the manifest detail and whether the asset has since left the library, and a
     * caller that re-stated them would drop whichever one it had not been taught about.
     *
     * Nothing competes for an `UPLOADED` row — [markTerminal] is guarded on `REQUESTED`, so the platform
     * callback cannot touch one — but the guard costs nothing and keeps both transitions the same shape.
     */
    suspend fun promoteUploaded(key: String): Boolean

    /**
     * The rows that **need an upload job**, in a stable key order, at most [limit] of them — the upload
     * cycle's source of work (capability `sync-ledger`).
     *
     * Returns exactly the rows whose state is in [app.snapsync.model.NEEDS_JOB_STATES], interpreting
     * nothing else: *which* states need a job is decided once, in `model/`, not per query. That set spans
     * `DISCOVERED` and `FAILED`, which are the same fact to a producer — a key with no live job and no
     * bytes on the backend — differing only in whether an attempt was already made.
     *
     * **Bounded, unlike [uploadedRows].** A first walk on a large library records a row per outstanding
     * resource, and a cycle that tried to enqueue all of them would stage every one of them to disk. The
     * bound is the caller's, because only the caller knows how many slots the platform will take.
     *
     * Absent rows are excluded: the asset has left the library, so there is nothing to upload from.
     */
    suspend fun rowsNeedingJob(limit: Int): List<LedgerEntry>

    /**
     * The `REQUESTED` keys — the candidates for the app-driven tier's stranded reconciliation
     * (`ios-url-session-upload`).
     *
     * Deliberately narrower than [pendingResources], which that tier used to read for this and which
     * returns the whole non-settled backlog. A `FAILED` row has already been adjudicated; re-surfacing it
     * every cycle re-writes the row, signals a change, and reports a loss that did not happen — a device
     * log shows one key "stranded" twelve times inside a single process, seven within sixteen seconds.
     * After `UPLOADED` exists the same read would also hand a freshly-uploaded row to the stranded pass,
     * which would then write it back to `FAILED` and destroy the fact this whole change makes durable.
     */
    suspend fun requestedKeys(): Set<String>

    /**
     * The `COMPLETED` rows that carry manifest detail — the **device manifest, projected**
     * (capability `device-manifest`). Rows still bare (no `creationDate`) are excluded: they are
     * mid-backfill, and listing a resource with no capture date would place it outside every
     * membership window rather than inside the right one.
     */
    suspend fun completedManifestRows(): List<LedgerEntry>

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
     * Dings [changes] like a [put] so watchers re-read the now-empty truth.
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
     * **once** on success, like a [put]. This is a reset-family op (alongside [clear]) — the app-side
     * join seed uses it; it is **not** a per-key record, so it does not breach the single-record-writer
     * invariant.
     */
    suspend fun resetTo(entries: List<LedgerEntry>)

    /**
     * Mark every row whose [LedgerEntry.assetId] equals [assetId] as [LedgerEntry.absent] — the asset has
     * left this device's library. The rows are **kept**: what they record (these bytes are on the
     * backend) is still true, and keeping them is what stops a restored asset re-uploading. The backend
     * matches by equality and never interprets the value — `assetId` is a second opaque grouping field
     * (it does not know what an "asset" means). Idempotent. Dings [changes] like a [put].
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
     * Rewrite the [LedgerEntry.eventId] of every row whose value is the pre-provenance sentinel
     * `""` to [eventId], leaving every other field — and every row already carrying a real
     * eventId — untouched. The backend matches the sentinel by equality and interprets nothing.
     * Idempotent (a sweep that matches no rows is a no-op) and cheap, so the writer runs it once
     * per cycle entry. Dings [changes] once, like the other bulk operations — provenance is
     * invisible to today's watchers, but the ding keeps the level-trigger contract uniform.
     */
    suspend fun backfillEventId(eventId: String)
}
