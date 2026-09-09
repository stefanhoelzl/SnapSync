package app.snapsync.feature.upload

import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.DeviceListingShapeException
import app.snapsync.ports.JoinedEventMarker

import app.snapsync.ports.LedgerStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.assetIdFromUploadKey
import co.touchlab.kermit.Logger
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Upper bound on the reconcile's network device-listing `LIST` (mirrors the device-manifest PUT guard).
 * Every upload tier runs inside a window the OS closes: the extension runner has a hard ~3-minute cap
 * (a hung `LIST` under `runBlocking` burns it and gets the worker force-killed), and the app-driven tier
 * has its `BGProcessingTask` window. Larger than the small manifest-PUT guard because the listing can
 * return tens of thousands of entries on a big library — but still bounds a genuinely stuck call. Only
 * the network call is bounded; the subsequent `resetTo` stays a single atomic, un-timed transaction.
 */
private const val DEVICE_LIST_TIMEOUT_MS = 30_000L

/**
 * Re-join reconciliation for the **upload tier** (capability `upload-state-reconciliation`) — whichever
 * process holds the `LedgerWriter`: the extension on iOS ≥26.1, the app on iOS 18–26.0. It is built
 * once in the shared `uploadCore`, so it runs on that tier's own cycle whichever tier that is, **before**
 * any upload job is created, and it decides whether the producer may upload this cycle. It was named for
 * the extension when only the extension had one; the app-driven tier shipped with no reconciliation at
 * all until `changes/archive/2026-07-12-fix-app-driven-upload-lifecycle` generalized it.
 *
 * The seed is a producer-side dedup optimization — it stops a re-joined / reinstalled device from
 * re-uploading already-stored bytes. It is **not** invisible to the UI: the upload arm's completed count
 * is a `LedgerStore.aggregates()` read (`LedgerBackedSyncStatusSource`, capability `sync-status`), so a
 * re-baseline that drops rows moves the reported progress with it. That is honest — those bytes really
 * are not stored — but it is a visible consequence, not a silent bookkeeping detail.
 *
 * Bytes are device-partitioned and **event-independent** (`/files/devices/<deviceId>/…`), so seeding from the
 * **device** listing (not a per-event one) is what preserves cross-event dedup: a switch re-seeds the
 * same files `COMPLETED`, so nothing already stored re-uploads. The seed is an **atomic
 * `resetTo` (clear-and-seed)**, not an additive upsert — the clear drops stale/phantom rows (e.g. a
 * `REQUESTED` row from a prior cycle whose job never materialized, which the engine would otherwise
 * treat as in-flight and skip forever), leaving the ledger as exactly the device's stored files.
 *
 * The **discovery cursor IS cleared** on a re-join, though: the cursor is what makes the next scan a
 * full re-enumeration rather than an incremental "what changed" pass, and a re-join needs to
 * re-enumerate to find the assets that still need uploading (the App-Group cursor survives an app
 * *upgrade*, so without the reset a re-join scans incrementally and discovers nothing). This is safe —
 * the reset+seeded ledger answers `AlreadyUploaded` for everything already stored, so a
 * re-enumeration re-uploads nothing; it only re-discovers genuinely-unstored work.
 *
 * - configured `eventId` == marker → already joined; upload directly (no fetch, seed, or cursor reset).
 * - configured `eventId` != marker (a switch, reinstall, or fresh provision) → fetch the **device's**
 *   stored filenames, **`resetTo`** one `COMPLETED` row per filename (clear-and-seed, key = filename),
 *   **clear the discovery cursor** (force a full re-enumeration), then set the marker. Returns `true`,
 *   so the same cycle proceeds to upload: seeded rows are skipped by the engine and any not-yet-stored
 *   resource uploads idempotently. The reset makes the ledger exactly the device's stored files on
 *   every re-join — restoring dedup after a reinstall and clearing any phantom in-flight rows.
 * - the listing fetch fails → create no jobs this cycle and leave the marker **unset** (the ledger and
 *   cursor are untouched), so the next cycle retries. There is no user-facing join-failure state. A
 *   listing this build cannot *read* defers the same way but is reported at `Error`, because unlike a
 *   transport failure it will not heal on the next cycle.
 * - no event configured but a marker remains (a leave) → clear the marker only and upload nothing; the
 *   ledger (global, valid across events) is left intact so a later re-join dedups against it.
 */
class UploadReconciler(
    private val files: DeviceFilesSource,
    private val ledger: LedgerStore,
    private val marker: JoinedEventMarker,
    private val deviceId: String,
    private val clearDiscoveryCursor: suspend () -> Unit,
    private val log: Logger = Logger.withTag("UploadReconciler"),
) {
    /**
     * Reconcile for the [configuredEventId] (`null` when no event is configured). Returns whether the
     * producer may create upload jobs this cycle.
     */
    suspend fun reconcile(configuredEventId: String?): Boolean {
        val marked = marker.read()
        if (configuredEventId == null) {
            // Leave (or never joined): forget the join marker so a later provision reconciles fresh. The
            // ledger is global (file rows valid across events), so it is left intact for re-join dedup.
            if (marked != null) {
                log.i { "no event configured but marker present — clearing the join marker" }
                marker.clear()
            }
            return false
        }
        if (configuredEventId == marked) return true // already joined → upload directly

        // Marker mismatch: a switch, reinstall, or fresh provision. Fetch BEFORE mutating so a failure
        // defers without settling — the ledger and marker are left untouched and the next cycle retries.
        // The network LIST is bounded by an explicit timeout so a hung fetch cannot stall the
        // OS-scheduled cycle to the force-kill; a timeout defers exactly like a failed fetch (no seed,
        // ledger/cursor/marker untouched, retry next cycle).
        val listing = withTimeoutOrNull(DEVICE_LIST_TIMEOUT_MS) { files.list(deviceId) }
        if (listing == null) {
            log.w { "device listing timed out — deferring uploads this cycle" }
            return false
        }
        val filenames = listing.getOrElse { cause ->
            // Two failures, two severities, because their consequences differ (`module-architecture`,
            // "Absence is never silent"). A transport failure is transient and heals on the next cycle,
            // so it stays a warning. A SHAPE failure never heals: this build cannot read what the
            // backend answers, and every cycle from here defers — a device that has silently stopped
            // uploading. That is a report, not a note, so it rides at `Error` and reaches crash
            // reporting (capability `crash-reporting`). Both defer identically; only the telling differs.
            if (cause is DeviceListingShapeException) {
                log.e(cause) { "device listing was not understood — deferring uploads, and this will not heal" }
            } else {
                log.w(cause) { "device listing fetch failed — deferring uploads this cycle" }
            }
            return false
        }
        // A SUCCESSFUL listing is AUTHORITATIVE — empty, partial, or full — so we reset to exactly what
        // it reports and never second-guess it. An empty (or short) listing while the ledger still holds
        // COMPLETED rows means those resources are GONE from the backend (a full/partial reset), not a
        // transient. The listing is a DATABASE read, and three facts make it trustworthy: the byte route
        // records the resource row NON-best-effort and answers 502 if it cannot (capability
        // `api-endpoints`), so a 2xx upload implies a committed row; the deployment is a single primary,
        // so a later read sees that row (capability `database`); and this listing route answers 502 —
        // surfaced above as a fetch failure — never an empty array — when its own query fails. So the
        // only untrustworthy signal is a fetch error/timeout, which already deferred above; a confirmed
        // listing re-baselines. This is what heals a stuck device after a reset: the missing resources
        // drop out of the ledger and re-upload.
        //
        // RESET the ledger to exactly the device's stored files — one COMPLETED row each — via an atomic
        // clear-and-seed, NOT an additive upsert. The clear also drops stale/phantom rows, e.g. a
        // REQUESTED row left by a prior cycle whose upload job never materialized (otherwise the engine
        // reads it as "in flight" and skips re-creating that upload forever). Seeding from the DEVICE
        // listing (global, event-independent) preserves cross-event dedup: a switch re-seeds the same
        // files COMPLETED, so nothing still stored re-uploads; a deleted or never-stored resource is
        // absent from the listing and uploads.
        // Seeds carry the reconciled event as provenance (`sync-ledger`): the seed IS this join's
        // write, so no seeded row ever needs the pre-provenance backfill sweep.
        val seeds = filenames.map {
            LedgerEntry(it, assetIdFromUploadKey(it), LedgerState.COMPLETED, attempt = 0, eventId = configuredEventId)
        }
        ledger.resetTo(seeds)
        // Force a full re-enumeration so the producer re-discovers the assets that still need
        // uploading — the cursor survives an app upgrade, so a re-join with a settled cursor would
        // otherwise scan incrementally and find nothing. The reset+seed dedups, so this re-uploads
        // nothing already stored.
        clearDiscoveryCursor()
        marker.set(configuredEventId) // settle even when the listing is empty → the next cycle does not re-loop
        log.i { "joined $configuredEventId — reset+seeded ${seeds.size} file(s), cleared cursor" }
        return true
    }
}
