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
     * settled row — a stale failure, a duplicate `REQUESTED` — would demand a job for bytes the backend
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

    /**
     * The ledger's whole-store truth, counted by photo. It counts EVERY row — the join-time load seeds the
     * device's stored resources for any event — so it is not the status read: its callers are the
     * extension's "work remains" check and the diagnostic dump. Status reads [assetProgress].
     */
    suspend fun aggregates(): LedgerAggregates

    /**
     * Per photo, whether **every** row of that asset is done: `assetId → done`, one entry per asset the ledger
     * holds a row for (capability `sync-ledger`, "Per-asset progress read"). The same per-asset collapse
     * [aggregates] performs, un-counted, in one snapshot-consistent read. Status intersects it with the
     * admitted set the gallery counted for `N`; the ledger interprets nothing about admission.
     */
    suspend fun assetProgress(): Map<String, Boolean>

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
     * nothing else: *which* states need a job is decided once, in `model/`, not per query. That set is
     * `DISCOVERED` — a key with no live job and no bytes on the backend, whether never attempted or returned
     * there by a failure.
     *
     * **Unbounded, deliberately.** A cycle does bound its work — a first walk on a large library records a
     * row per outstanding resource, and enqueuing all of them would stage every one to disk — but it
     * bounds what it **resolves**, never what it reads, because a row needing a job is not yet the
     * admitted set (capability `photo-selection-policy`). A bound here would starve: rows come back in a
     * stable key order, so rows the membership's current policy excludes, sorting ahead of admitted ones,
     * would fill the slice on every cycle and the admitted work further down would never be reached. The
     * scan is local and indexed; the platform round-trip the bound protects is the caller's to make.
     */
    suspend fun rowsNeedingJob(): List<LedgerEntry>

    /**
     * The rows the **device manifest** projects from (capability `device-manifest`): every row,
     * whatever its upload state.
     *
     * Deliberately **not state-scoped**, and deliberately carrying no state adjective in its name. The
     * manifest declares what this member *intends to provide*, and that does not depend on how far a
     * resource's bytes have got — so a `DISCOVERED` row and a `COMPLETED` one are equally listed. This
     * read used to return only settled rows, and the stale word "completed" in its name outlived the
     * decision behind it: `api-endpoints` came to describe a manifest that declares intent while
     * `device-manifest` still required the completed projection.
     *
     * It filters on nothing: a departed asset's rows are deleted by the walk that shows it gone, so every row
     * is one this device still holds. **Admission is the policy's**, applied by the projection: the
     * capture-date bounds, and with them the exclusion of a row whose `creationDate` is still bare, whose
     * empty value sorts before every real cutoff. Restating that here would be a second copy of an
     * admission rule (capability `photo-selection-policy`).
     */
    suspend fun manifestRows(): List<LedgerEntry>

    /**
     * Fill the manifest detail of one already-recorded row **without touching its state**,
     * and only while the row is still bare — so re-running is free and can never clobber a good value.
     *
     * The sweep for the two ways a row rests bare: it predates the 5.sqm migration, or the re-join
     * reconcile seeded it from a stored-file listing (filenames carry no capture date). A writer-family
     * operation like [deleteKeys]: only the single writer's cycle runs it.
     */
    suspend fun backfillManifestDetail(entry: LedgerEntry)

    /**
     * Delete every row — a deliberate reset (the app re-provisioning config), not a sync write.
     * Dings [changes] so watchers re-read the now-empty truth.
     */
    suspend fun clear()

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
}
