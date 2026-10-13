package app.snapsync.services.upload

import app.snapsync.model.CycleResult
import app.snapsync.services.ledger.LedgerService
import co.touchlab.kermit.Logger

/**
 * The OS-driven tier's pending→re-invocation rule (drained from
 * the untested extension root at the migration finale): the OS invokes the extension lazily (on
 * library changes), not when an upload quietly finishes — so a drained cycle that returns
 * [CycleResult.COMPLETED] leaves already-succeeded jobs un-acknowledged until the next change.
 * While the ledger still has pending (in-flight) rows, answer [CycleResult.PROCESSING] to request
 * another invocation so their completions are recorded promptly; report [CycleResult.COMPLETED]
 * only once everything is uploaded (pending == 0), so the system then rests. (The OS throttles
 * re-invocation, so this polls at its cadence, not in a loop.) This tier alone needs it — it
 * cannot observe a completion while not running; the app-driven tier's pump can.
 *
 * The [ledger]'s pending count is read **only** on a completed cycle (a skipped/failed/processing result already
 * carries its re-arm answer).
 */
suspend fun CycleResult.requeueWhilePending(ledger: LedgerService, log: Logger): CycleResult {
    if (this != CycleResult.COMPLETED) return this
    val open = ledger.aggregates().pending
    if (open <= 0) return this
    log.i { "process: $open pending — requesting re-invocation" }
    return CycleResult.PROCESSING
}
