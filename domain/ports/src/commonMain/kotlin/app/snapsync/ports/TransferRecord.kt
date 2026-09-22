package app.snapsync.ports

import app.snapsync.model.LedgerEntry
import app.snapsync.model.TerminalOutcome

/**
 * The narrow ledger surface a **transport** receives (capability `sync-ledger`, "Reader and writer capability
 * split"): the one guarded terminal write a platform callback records through, and the one row read that
 * resolves a returned job to its row — by destination. [LedgerStore] extends it; a transport is handed this and
 * nothing wider.
 *
 * It adds no logic and decides nothing — it **restricts**. A transport still records the terminal fact the
 * platform hands it, because that write must land before a non-suspending callback returns, and no call into
 * the core can be made from there: a callback into a feature would bypass "Commands cross one door"
 * (`module-architecture`), and every flow command suspends. What a transport can no longer reach is every other
 * read and write of the ledger.
 *
 * Decision record: `changes/transport-only-seam` (D4, D5).
 */
interface TransferRecord {

    /**
     * The row whose upload was addressed to [destinationPath], or null when no row records it.
     *
     * This is how a returned platform upload job is resolved back to its row: the destination is the only
     * field a succeeded job reliably carries, and under a byte route that names identity in its path the
     * ledger key is no longer recoverable from it (capability `ios-photokit-upload`).
     *
     * A row recorded before the ledger kept a destination carries null and is never matched here, and no
     * other route reaches it: the v1 last-segment fallback is retired (decision record
     * `changes/retire-legacy-key-fallback`).
     *
     * Null is also how a transport tells a job whose row an authoritative walk deleted — its photo left the
     * library or the selection, possibly mid-upload — from one it can still settle.
     */
    suspend fun entryForDestination(destinationPath: String): LedgerEntry?

    /**
     * Record how one upload terminated — [outcome]'s state becomes the row's (`COMPLETED` for a success; a
     * failure returns the row to `DISCOVERED`) — but **only while that row is still `REQUESTED`**; answers
     * whether it applied. [TerminalOutcome] fixes that set, so a callback can never claim a job exists
     * (`REQUESTED`) through this verb.
     *
     * The guard is the operation's purpose. Several writers reach a row holding no shared lock — a platform
     * callback recording that an upload terminated, on the platform's own queue; the upload cycle on its lane;
     * and, on iOS ≥26.1 under a full grant, the other process's cycle and callbacks over the same App-Group
     * ledger (decision record `changes/both-uploaders-active`) — so a read-then-write pair is not atomic against
     * the one that does not take the lock. A duplicate completion of the same key (two uploaders overlapping)
     * applies once and answers `false` the second time. Putting the condition in the write is what makes a fact recorded underneath
     * a stale read impossible to clobber. (`photo-download` reached the same conclusion for the same
     * reason: *"the guard SHALL live in the store's write rather than in a caller's preceding read"*.)
     *
     * Every other column is preserved by the backend rather than re-supplied here: the caller is a delegate
     * that holds only the key and cannot re-state `assetId`, the destination or the manifest detail.
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
     * "Reader and writer capability split". Do not add a second record operation here, or on [LedgerStore],
     * on this argument.
     */
    fun markTerminal(key: String, outcome: TerminalOutcome): Boolean
}
