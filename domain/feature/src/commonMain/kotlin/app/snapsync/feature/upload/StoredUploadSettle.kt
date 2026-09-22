package app.snapsync.feature.upload

import app.snapsync.model.TerminalOutcome
import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.DeviceListingShapeException
import app.snapsync.ports.LedgerStore
import co.touchlab.kermit.Logger
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Upper bound on the listing, the same as the join-time load's. The answer is small — uploads are bounded by an
 * event window of at most 30 days (1.67 s for 1,433 rows measured on an SE2) — so it bounds a stuck call, not a
 * slow one.
 */
private const val LISTING_TIMEOUT_MS = 15_000L

/**
 * The foreground settle (capability `upload-state-reconciliation`, "Foreground settles in-flight rows the backend
 * already stores"): ask the backend which resources it stores for this device, and record `COMPLETED` for every
 * `REQUESTED` row whose key it lists.
 *
 * **Why.** Bytes can land long before the OS acknowledges their job, and sometimes the acknowledgement never
 * reaches this ledger. Under a full grant the extension learns of a completion only at its next invocation; after
 * a downgrade to a partial grant it is withheld and presented nothing — measured on an SE2 (iOS 26.6, 2026-09-22):
 * four objects landed within ~30 s while their rows stayed `REQUESTED` and the status read `Syncing` until full
 * access returned. The backend is the one party that knows.
 *
 * **What it may write, and why it needs no lock.** Only through the guarded [LedgerStore.markTerminal], which
 * applies only while a row is still `REQUESTED`: a listed `DISCOVERED` row, a settled row, and a row deleted
 * meanwhile are all left exactly as they are, and a later OS acknowledgement finds a settled row and does nothing.
 * That is the same write the platform's callbacks already make beside a running cycle, so this runs beside the
 * pump rather than behind it (the pump can await one cycle for many minutes after a long suspension, and this
 * exists to correct the status on return). It never marks anything done without the listing naming its stored
 * bytes, and it seeds, resets, deletes and fails nothing.
 *
 * **When.** Foreground, in the app process only; never inside a cycle (which does not fetch the listing) and never
 * in the extension. A failure leaves nothing behind — no flag, no retry — and the next foreground asks again.
 * It never throws: a foreground entry completes whatever the listing does.
 *
 * Decision record: `changes/selection-is-the-walk` (D4).
 */
class StoredUploadSettle(
    private val files: DeviceFilesSource,
    private val ledger: LedgerStore,
    /** The device identity; a thunk because it resolves against a protected store on first use. */
    private val deviceId: () -> String,
    private val log: Logger = Logger.withTag("StoredUploadSettle"),
) {
    suspend fun settle() {
        try {
            val pending = ledger.pendingResources().mapTo(mutableSetOf()) { it.key }
            if (pending.isEmpty()) return // nothing in flight could be settled: no request
            val listing = withTimeoutOrNull(LISTING_TIMEOUT_MS) { files.list(deviceId()) }
            if (listing == null) {
                log.w { "device listing timed out — nothing settled this foreground" }
                return
            }
            val stored = listing.getOrElse { reportFailure(it); return }
            val settled = stored.map { it.key }.filter { it in pending }
                .count { ledger.markTerminal(it, TerminalOutcome.COMPLETED) }
            if (settled > 0) log.i { "settled $settled in-flight row(s) whose bytes the backend already stores" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportFailure(e)
        }
    }

    private fun reportFailure(cause: Throwable) {
        if (cause is DeviceListingShapeException) {
            log.e(cause) { "device listing was not understood — nothing settled this foreground" }
        } else {
            log.w(cause) { "device listing fetch failed — nothing settled this foreground" }
        }
    }
}
