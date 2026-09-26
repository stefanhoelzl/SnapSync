package app.snapsync.services.upload

import app.snapsync.model.CycleResult

/**
 * The OS-driven tier's pending→re-invocation rule (capability `background-upload`; drained from
 * the untested extension root at the migration finale): the OS invokes the extension lazily (on
 * library changes), not when an upload quietly finishes — so a drained cycle that returns
 * [CycleResult.COMPLETED] leaves already-succeeded jobs un-acknowledged until the next change.
 * While the ledger still has pending (in-flight) rows, answer [CycleResult.PROCESSING] to request
 * another invocation so their completions are recorded promptly; report [CycleResult.COMPLETED]
 * only once everything is uploaded (pending == 0), so the system then rests. (The OS throttles
 * re-invocation, so this polls at its cadence, not in a loop.) This tier alone needs it — it
 * cannot observe a completion while not running; the app-driven tier's pump can.
 *
 * [pending] is consulted **only** on a completed cycle (a skipped/failed/processing result already
 * carries its re-arm answer); [onRequeue] is a diagnostics hook for the debug.log line.
 */
suspend fun CycleResult.requeueWhilePending(
    pending: suspend () -> Int,
    onRequeue: (Int) -> Unit = {},
): CycleResult {
    if (this != CycleResult.COMPLETED) return this
    val open = pending()
    if (open <= 0) return this
    onRequeue(open)
    return CycleResult.PROCESSING
}

/**
 * One OS-driven `process()` invocation — [run] the cycle, then [requeueWhilePending] — as a function
 * that **never throws** (capability `background-upload`). The extension root forwards its result
 * across the ObjC boundary, where a Kotlin throwable is not a failed cycle but a Kotlin/Native
 * `abort()` of the whole extension process: no result reaches the OS and nothing is reported.
 *
 * A throw from the cycle is reported through [onCycleFailed]; a throw from anything after it — the
 * [pending] ledger read, or a hook — through [onLateFailure]. Both answer [CycleResult.FAILED]: a
 * cycle whose bookkeeping could not be completed cannot claim `COMPLETED` (it may rest with jobs in
 * flight), and `PROCESSING` means "more work", not "error". The one path left unguarded is
 * [onLateFailure] itself, the last resort.
 *
 * Guarding the whole body rather than the one known read is the point: the defect was a shape — any
 * line after the cycle's own guard could abort the process — not a single bad call.
 */
suspend fun runProcessCycle(
    run: suspend () -> CycleResult,
    pending: suspend () -> Int,
    onCycleFinished: (CycleResult) -> Unit = {},
    onCycleFailed: (Throwable) -> Unit = {},
    onRequeue: (Int) -> Unit = {},
    onLateFailure: (Throwable) -> Unit = {},
): CycleResult =
    // Catches EVERYTHING, cancellation included, and deliberately: this is an ObjC boundary, where a Kotlin
    // throwable — a `CancellationException` no less than any other — aborts the extension process. The one
    // sanctioned catch-all outside the `model/` helpers (named in the catch gate).
    try {
        val ran = try {
            run()
        } catch (t: Throwable) {
            onCycleFailed(t)
            null
        }
        // The hook runs outside the cycle's own guard, so a throwing hook is a LATE failure, not a failed cycle.
        ran?.also(onCycleFinished)?.requeueWhilePending(pending, onRequeue) ?: CycleResult.FAILED
    } catch (t: Throwable) {
        onLateFailure(t)
        CycleResult.FAILED
    }
