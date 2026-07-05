package app.snapsync.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The device's own-device upload counts (`sync-status`), both **asset-counted** and read from a
 * **single** ledger `aggregates()` round-trip so they are mutually consistent:
 *
 * - [completed] = photos all of whose ledger rows are `COMPLETED`.
 * - [pending] = photos with any non-`COMPLETED` ledger row (a job created but not yet done).
 *
 * The two asset sets are disjoint; an undiscovered photo (no ledger row) is in neither.
 */
data class LedgerCounts(val completed: Int, val pending: Int) {
    companion object {
        val ZERO = LedgerCounts(completed = 0, pending = 0)
    }
}

/**
 * The seam the status projection reads for own-device completeness **and** in-flight activity. It
 * exposes **counts only** — never the ledger nor any write capability — so the status domain keeps no
 * `:domain:engine` dependency and the extension stays the sole ledger writer. [counts] is a
 * level-triggered value; [refresh] re-reads it. It refreshes on **foreground entry**, on the
 * extension's cross-process liveness notification, and (app-driven tier) after each pump cycle.
 */
interface LedgerCountsSource {
    val counts: StateFlow<LedgerCounts>
    suspend fun refresh()
}

/**
 * The real [LedgerCountsSource]: [refresh] calls the injected [read] (on iOS, a **read-only** read of
 * the shared App-Group ledger's `aggregates()`, mapped to [LedgerCounts]) and publishes the result. The
 * read is a `suspend () -> LedgerCounts` so the engine/ledger types never reach `:domain:status` — the
 * composition root supplies the read, keeping this logic platform-free and testable.
 *
 * On any read failure the **last good value is retained** (never regressed to zero) — a transient read
 * error must not drop `completed` and falsely flip the screen out of "In sync". The seed before any
 * successful read is [LedgerCounts.ZERO].
 */
class ReadingLedgerCountsSource(private val read: suspend () -> LedgerCounts) : LedgerCountsSource {
    private val _counts = MutableStateFlow(LedgerCounts.ZERO)
    override val counts: StateFlow<LedgerCounts> = _counts.asStateFlow()

    override suspend fun refresh() {
        runCatching { read() }.getOrNull()?.let { _counts.value = it }
    }
}

/**
 * A settable, in-memory [LedgerCountsSource]: holds its counts synchronously and re-emits on [set].
 * Used by the desktop harness and tests; the iOS app backs the seam with [ReadingLedgerCountsSource]
 * over the read-only ledger aggregate. [refresh] is inert here.
 */
class MutableLedgerCountsSource(initial: LedgerCounts = LedgerCounts.ZERO) : LedgerCountsSource {
    private val _counts = MutableStateFlow(initial)
    override val counts: StateFlow<LedgerCounts> = _counts.asStateFlow()

    override suspend fun refresh() = Unit

    fun set(completed: Int, pending: Int) {
        _counts.value = LedgerCounts(completed = completed, pending = pending)
    }
}
