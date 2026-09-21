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

    /**
     * [recordUnlessSettled] for every entry of [entries], under the same done-state guard per entry, in **one
     * storage transaction**: either every applicable entry lands or — on failure — none does. Answers how many
     * applied, and dings [changes] once if any did.
     *
     * The atomicity is load-bearing, not an optimization. A walk re-reads only the assets the ledger does not
     * fully know (capability `sync-ledger`), so an asset whose resources were recorded one write at a time
     * could be left with one role recorded when the process died between them — and every later walk would
     * then skip it as known, and its other role would never upload. Recorded together, a walk's discoveries
     * land whole or not at all.
     */
    suspend fun recordAllUnlessSettled(entries: List<LedgerEntry>): Int

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
     * Mark every `REQUESTED` row `FAILED`, changing nothing else on those rows and leaving every other row
     * untouched — an **app-side reset-family** op (alongside [clear]), not a per-key record, so a non-writer
     * may run it (on iOS ≥26.1 the app, while the extension is the one recording process).
     *
     * The recovery for `REQUESTED` rows no transfer can settle any more — canonically the jobs the OS wiped
     * when the extension was disabled: the engine never re-issues a `REQUESTED` key and no API surfaces the
     * vanished job. It demotes rather than deletes because a `FAILED` row needs a job, so the ledger's own
     * work read ([rowsNeedingJob]) returns it on the next cycle — no walk, and so no discovery-cursor reset.
     * Dings [changes] once, like [clear] (capability `sync-ledger`, "Requested-state reset").
     */
    suspend fun demoteRequested()

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
     * Delete exactly the rows whose key is among [keys], whatever their state, and no other — the one row
     * deletion a cycle performs (capability `sync-ledger`, "Deletion is a presence diff over an authoritative
     * walk").
     *
     * **Key-scoped, never asset-scoped.** Several resources of one photo share an `assetId` and hold per-key
     * states, and every caller holds evidence about individual rows: a key that resolved to nothing, or a row
     * an authoritative walk did not return. An asset-scoped delete driven by a key-grained read reaches rows
     * the read never selected — a Live Photo's `COMPLETED` primary, deleted because its paired video's key
     * failed to resolve under a partial grant.
     *
     * Writes nothing and dings nothing when none of [keys] has a row. Accepts more keys than one storage
     * statement binds. A writer-family operation: only the single writer's cycle runs it.
     */
    suspend fun deleteKeys(keys: Collection<String>)

    /**
     * Clear the retired absence mark from every row an earlier build marked, touching nothing else.
     *
     * No operation sets the mark any more — a departed asset's rows are deleted, not marked — but every read
     * that answers "what does this device hold or share" still excludes marked rows, so a row an earlier
     * build marked would be unreachable forever without this. Cleared, it heals itself: a row that still
     * needs a job is offered again, fails to resolve, and is deleted by key; a settled one is deleted by the
     * next authoritative walk if its asset is gone.
     *
     * One idempotent statement, run once per cycle beside [backfillEventId]; it matches nothing on every cycle
     * after the first. Deliberately NOT a schema migration: a migration raises the schema version, which an
     * older binary refuses to open. Removed with the column by the migration that drops it. Dings [changes]
     * only when it cleared a mark.
     */
    suspend fun clearAbsenceMarks()

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
