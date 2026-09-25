package app.snapsync.feature.membership

import app.snapsync.ports.DeviceIdentity
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.DeviceListingShapeException
import app.snapsync.ports.LedgerStore
import co.touchlab.kermit.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Upper bound on the join-time listing. The join surface is waiting on it, and uploads are bounded by an
 * event window of at most 30 days, so the answer is small: phase 1 measured 1.67 s for 1,433 rows on an SE2
 * (`changes/archive/2026-09-21-always-full-enumerate`). The bound exists for a stuck call, not a slow one.
 */
private const val LISTING_TIMEOUT_MS = 15_000L

/**
 * The join-time load (capability `photo-sharing`, "A join loads the ledger from the per-device
 * listing"): at a provision into a **new** membership — a first join or a switch, never a re-provision of
 * the joined event — make the upload ledger this membership's share set.
 *
 * The ledger is the current membership's share set (capability `photo-sharing`), so a new membership starts
 * with **nothing from before it**. On a confirmed listing the ledger becomes exactly the device's stored
 * resources, one bare `COMPLETED` row each, in one atomic `resetTo` — so nothing the backend already holds
 * is uploaded again, whichever event it was stored for. On a failed or timed-out listing it becomes empty.
 *
 * **Why the clear happens even when the fetch fails.** A leftover `COMPLETED` row inside the new window
 * suppresses a needed upload forever, with no error: the invisible failure this product is built against.
 * Such a row survives on a device that left an event under the contract that kept the ledger across a leave
 * (and whose bytes the sweep then collected), and after a leave whose best-effort clear failed. Clearing
 * here closes both with no extra concept.
 *
 * **Why a failure blocks nothing.** There is no flag, no gate and no "load owed" bit. The only cost of a
 * failed load is that the walk records the window's photos as new work and uploads them again — idempotent
 * overwrites of the same `(deviceId, assetId, role)` objects, bounded by the event window. Retry state
 * could strand a device behind a gate; this cost cannot.
 *
 * It never throws: the join it belongs to completes whatever the listing does. A transport failure or a
 * timeout is a warning; a listing this build cannot **read** is an `Error`, because unlike the network it
 * will not heal, and every future join would pay the full re-upload.
 */
class ShareSetLoad(
    private val files: DeviceFilesSource,
    private val ledger: LedgerStore,
    /** The device identity; a thunk because it resolves against a protected store on first use. */
    private val identity: DeviceIdentity,
    private val log: Logger = Logger.withTag("ShareSetLoad"),
) {
    suspend fun load() {
        // `list` answers failures as a `Result`; a throw (the device-id read, say) is folded into the same
        // arm. Cancellation is not a failure and is rethrown — the caller is being torn down.
        val listing = try {
            withTimeoutOrNull(LISTING_TIMEOUT_MS) { files.list(identity.deviceId()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val stored = when {
            listing == null -> {
                log.w { "device listing timed out — starting this membership from an empty ledger" }
                null
            }
            listing.isFailure -> {
                val cause = listing.exceptionOrNull()
                if (cause is DeviceListingShapeException) {
                    log.e(cause) {
                        "device listing was not understood — starting from an empty ledger; this device " +
                            "re-uploads what the backend already holds, and every join will until this is fixed"
                    }
                } else {
                    log.w(cause) { "device listing fetch failed — starting this membership from an empty ledger" }
                }
                null
            }
            else -> listing.getOrThrow()
        }
        try {
            if (stored == null) {
                ledger.clear()
            } else {
                ledger.resetTo(stored.map { LedgerEntry(it.key, it.assetId, LedgerState.COMPLETED) })
                log.i { "loaded the share set — ${stored.size} stored resource(s) seeded COMPLETED" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "could not reset the upload ledger at the join" }
        }
    }
}
