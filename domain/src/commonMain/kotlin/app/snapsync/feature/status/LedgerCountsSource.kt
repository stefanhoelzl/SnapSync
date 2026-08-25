package app.snapsync.feature.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The device's own-device upload counts (`sync-status`), both **asset-counted** and read from a
 * **single** ledger `aggregates()` round-trip so they are mutually consistent:
 *
 * - [completed] = photos all of whose ledger rows are `COMPLETED`.
 * - [pending] = photos with any non-`COMPLETED` ledger row (a job created but not yet done).
 * - [read] = whether these counts came from the ledger at all. See [LedgerCounts.UNREAD].
 *
 * The two asset sets are disjoint; an undiscovered photo (no ledger row) is in neither.
 */
data class LedgerCounts(val completed: Int, val pending: Int, val read: Boolean = true) {

    companion object {
        /**
         * The value before any successful read: **un-read**, not a ledger holding nothing.
         *
         * The two have different consequences, so they are different values (law "Absence is never
         * silent"). `completed = 0` from a real read means "none of your photos are recorded yet";
         * `UNREAD` means "we have not looked". The status projection settles to "In sync" when the
         * synced count reaches the total, so a seed that claims to be a read `(0, 0)` — beside a
         * gallery total that is also un-counted — renders a checkmark on a device that has read
         * nothing (`SNAPSYNC-14`, `SNAPSYNC-16`). Only [UNREAD] holds the projection at
         * `SyncStatus.Loading`; a read `(0, 0)` is a real answer and mints a snapshot.
         */
        val UNREAD = LedgerCounts(completed = 0, pending = 0, read = false)

        /** A genuine, ledger-derived zero — distinct from [UNREAD]. */
        val ZERO = LedgerCounts(completed = 0, pending = 0)
    }
}

/**
 * The seam the status projection reads for own-device completeness **and** in-flight activity. It
 * exposes **counts only** — never the ledger nor any write capability — so the status domain keeps no
 * engine dependency and the extension stays the sole ledger writer. [counts] is a
 * level-triggered value; [refresh] re-reads it. It refreshes on **foreground entry**, on each
 * [LedgerCountsPoller] tick while foregrounded (migration step 12 — the cross-process ding's
 * replacement), and (app-driven tier) after each pump cycle.
 */
interface LedgerCountsSource {
    val counts: StateFlow<LedgerCounts>
    suspend fun refresh()
}

/**
 * The real [LedgerCountsSource]: [refresh] calls the injected [read] (on iOS, a **read-only** read of
 * the shared App-Group ledger's `aggregates()`, mapped to [LedgerCounts]) and publishes the result. The
 * read is a `suspend () -> LedgerCounts` so the engine/ledger types never reach feature/status — the
 * composition root supplies the read, keeping this logic platform-free and testable.
 *
 * On any read failure the **last good value is retained** (never regressed to zero) — a transient read
 * error must not drop `completed` and falsely flip the screen out of "In sync". The seed before any
 * successful read is [LedgerCounts.UNREAD] — *not read*, which is a different answer from a ledger
 * holding nothing, and the difference is what keeps the screen from settling over counts nobody took.
 * A failed read therefore leaves an un-read source un-read, rather than promoting it to a read zero.
 */
class ReadingLedgerCountsSource(private val read: suspend () -> LedgerCounts) : LedgerCountsSource {
    private val _counts = MutableStateFlow(LedgerCounts.UNREAD)
    override val counts: StateFlow<LedgerCounts> = _counts.asStateFlow()

    override suspend fun refresh() {
        // `read = true` is stamped HERE, at the one place a value can come from the ledger, rather than
        // trusted from the injected read — so no caller can mint a value that claims to have been read.
        runCatching { read() }.getOrNull()?.let { _counts.value = it.copy(read = true) }
    }
}

/**
 * A settable, in-memory [LedgerCountsSource]: holds its counts synchronously and re-emits on [set].
 * Used by the desktop harness and tests; the iOS app backs the seam with [ReadingLedgerCountsSource]
 * over the read-only ledger aggregate. [refresh] is inert here.
 */
class MutableLedgerCountsSource(initial: LedgerCounts = LedgerCounts.UNREAD) : LedgerCountsSource {
    private val _counts = MutableStateFlow(initial)
    override val counts: StateFlow<LedgerCounts> = _counts.asStateFlow()

    override suspend fun refresh() = Unit

    /** Publish counts as a **read** value — stating a count is what a caller of this means. */
    fun set(completed: Int, pending: Int) {
        _counts.value = LedgerCounts(completed = completed, pending = pending)
    }
}
