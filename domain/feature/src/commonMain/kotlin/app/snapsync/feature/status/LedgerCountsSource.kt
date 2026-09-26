package app.snapsync.feature.status

import app.snapsync.model.AssetId
import app.snapsync.model.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ledger's per-photo done-ness (`sync-status`), read from a **single** ledger `assetProgress()`
 * round-trip so the two sets are mutually consistent:
 *
 * - [done] = the `assetId`s all of whose ledger rows are `COMPLETED`.
 * - [pending] = the `assetId`s with any non-`COMPLETED` ledger row (a job created but not yet done).
 * - [read] = whether these came from the ledger at all. See [LedgerCounts.UNREAD].
 *
 * The two sets are disjoint; an undiscovered photo (no ledger row) is in neither. They are NOT counted
 * here: the ledger holds rows for everything this device has stored for any event (the join-time load
 * seeds them all), so a whole-ledger count would mask pending in-window photos behind historical
 * completions. The ledger-backed status source counts them against the admitted set `N` counts.
 */
data class LedgerCounts(val done: Set<AssetId>, val pending: Set<AssetId>, val read: Boolean = true) {

    companion object {
        /**
         * The value before any successful read: **un-read**, not a ledger holding nothing.
         *
         * The two have different consequences, so they are different values (law "Absence is never
         * silent"). No done photo from a real read means "none of your photos are recorded yet";
         * `UNREAD` means "we have not looked". The status projection settles to "In sync" when the
         * synced count reaches the total, so a seed that claims to be a read empty answer — beside a
         * gallery total that is also un-counted — renders a checkmark on a device that has read
         * nothing (`SNAPSYNC-14`, `SNAPSYNC-16`). Only [UNREAD] holds the projection at
         * `SyncStatus.Loading`; a read empty answer is a real answer and mints a snapshot.
         */
        val UNREAD = LedgerCounts(done = emptySet(), pending = emptySet(), read = false)

        /** A genuine, ledger-derived empty answer — distinct from [UNREAD]. */
        val ZERO = LedgerCounts(done = emptySet(), pending = emptySet())

        /** Split one `assetProgress()` answer (`assetId → done`) into the two sets. */
        fun of(progress: Map<AssetId, Boolean>): LedgerCounts = LedgerCounts(
            done = progress.filterValues { it }.keys,
            pending = progress.filterValues { !it }.keys,
        )
    }
}

/**
 * The seam the status projection reads for own-device completeness **and** in-flight activity. It
 * exposes **per-photo done-ness only** — never the ledger's rows nor any write capability — so the status domain keeps no
 * engine dependency and the extension stays the sole ledger writer. [counts] is a
 * level-triggered value; [refresh] re-reads it. It refreshes on **foreground entry**, on each
 * [StatusCountsPoller] tick while foregrounded (migration step 12 — the cross-process ding's
 * replacement), and (app-driven tier) after each pump cycle.
 */
interface LedgerCountsSource {
    val counts: StateFlow<LedgerCounts>
    suspend fun refresh()
}

/**
 * The real [LedgerCountsSource]: [refresh] calls the injected [read] (on iOS, a **read-only** read of
 * the shared App-Group ledger's `assetProgress()`, mapped to [LedgerCounts]) and publishes the result. The
 * read is a `suspend () -> LedgerCounts` so the engine/ledger types never reach feature/status — the
 * composition root supplies the read, keeping this logic platform-free and testable.
 *
 * On any read failure the **last good value is retained** (never regressed to empty) — a transient read
 * error must not drop the done set and falsely flip the screen out of "In sync". The seed before any
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
        runCatchingCancellable { read() }.getOrNull()?.let { _counts.value = it.copy(read = true) }
    }
}

/**
 * A settable, in-memory [LedgerCountsSource]: holds its counts synchronously and re-emits on [set].
 * Used by the desktop harness and tests; the iOS app backs the seam with [ReadingLedgerCountsSource]
 * over the read-only per-asset progress read. [refresh] is inert here.
 */
class MutableLedgerCountsSource(initial: LedgerCounts = LedgerCounts.UNREAD) : LedgerCountsSource {
    private val _counts = MutableStateFlow(initial)
    override val counts: StateFlow<LedgerCounts> = _counts.asStateFlow()

    override suspend fun refresh() = Unit

    /** Publish done-ness as a **read** value — stating it is what a caller of this means. */
    fun set(done: Set<AssetId>, pending: Set<AssetId>) {
        _counts.value = LedgerCounts(done = done, pending = pending)
    }
}
