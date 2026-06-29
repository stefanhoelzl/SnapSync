package app.snapsync.rejoin

import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerEntry
import app.snapsync.engine.LedgerState
import co.touchlab.kermit.Logger

/**
 * The persisted join marker: the last `eventId` the extension reconciled, surviving the extension's
 * short-lived per-cycle process. It — **not** ledger-emptiness — is the join signal: a fresh join
 * that seeds zero rows still sets it (so there is no re-seed loop on an empty/large library), an
 * event switch is a marker mismatch, and a reinstall is an absent marker. Backed by the App-Group
 * `NSUserDefaults` on iOS, exactly as the discovery cursor is.
 */
interface JoinedEventMarker {
    fun read(): String?
    fun set(eventId: String)
    fun clear()
}

/**
 * Extension-side re-join reconciliation (capability `event-rejoin-reconciliation`). Runs on the
 * extension's own cycle, **before** any upload job is created, and decides whether the producer may
 * upload this cycle. The seed is a pure producer-side dedup optimization — it stops a re-joined /
 * reinstalled device from re-uploading already-stored bytes; status is read from storage truth, not
 * from this ledger (see `sync-status`), so the seed has no UI role and narrates nothing.
 *
 * - configured `eventId` == marker → already joined; upload directly (no fetch, enumeration, or seed).
 * - configured `eventId` != marker (a switch, reinstall, or fresh provision) → fetch the event's
 *   complete-asset listing and atomically reset the ledger to one `COMPLETED` row per resource (the
 *   reset replaces any prior event's rows), clear the discovery cursor, reset the per-asset manifest
 *   markers, and set the marker. Returns `true`, so the same cycle proceeds to upload: the seeded rows
 *   are skipped by the engine and any resource absent from the listing (partially-stored /
 *   never-uploaded) re-uploads idempotently.
 * - the listing fetch fails → create no jobs this cycle and leave the marker **unset** (the ledger is
 *   untouched), so the next cycle retries. There is no user-facing join-failure state.
 * - no event configured but a marker remains (a leave) → reset the ledger, clear the cursor and the
 *   manifest markers and the marker, and upload nothing, so a later provision of any event reconciles
 *   fresh.
 *
 * The [resetManifests] reset matters on an event **switch**: the per-asset manifest dedup markers are
 * keyed by `assetId`, not by event, so without clearing them on a reset a device switching to a new
 * event would skip re-uploading its manifests and the new event's assets would never read as complete.
 */
class ExtensionReconciler(
    private val files: EventFilesSource,
    private val ledger: LedgerBackend,
    private val marker: JoinedEventMarker,
    private val clearDiscoveryCursor: suspend () -> Unit,
    private val resetManifests: suspend () -> Unit,
    private val log: Logger = Logger.withTag("ExtensionReconciler"),
) {
    /**
     * Reconcile for the [configuredEventId] (`null` when no event is configured). Returns whether the
     * producer may create upload jobs this cycle.
     */
    suspend fun reconcile(configuredEventId: String?): Boolean {
        val marked = marker.read()
        if (configuredEventId == null) {
            // Leave (or never joined): a lingering marker means a previously-joined event's private
            // ledger is still around — reset it and clear the marker so a later provision reconciles
            // fresh. Nothing to upload either way.
            if (marked != null) {
                log.i { "no event configured but marker present — resetting ledger and clearing marker" }
                ledger.resetTo(emptyList())
                clearDiscoveryCursor()
                resetManifests()
                marker.clear()
            }
            return false
        }
        if (configuredEventId == marked) return true // already joined → upload directly

        // Marker mismatch: a switch, reinstall, or fresh provision. Fetch BEFORE mutating so a failure
        // defers without settling — the ledger and marker are left untouched and the next cycle retries.
        val remote = files.list(configuredEventId).getOrElse {
            log.w(it) { "listing fetch failed for $configuredEventId — deferring uploads this cycle" }
            return false
        }
        // Seed straight from the listing: it returns only complete assets, each carrying its assetId and
        // its resources' filenames, so one COMPLETED row per resource is all the seed needs — no local
        // enumeration. The atomic reset replaces any prior event's rows (the switch reset) in one step.
        val seeds = remote.flatMap { asset ->
            asset.resources.map { resource ->
                LedgerEntry(resource.filename, asset.assetId, LedgerState.COMPLETED, attempt = 0)
            }
        }
        ledger.resetTo(seeds)
        clearDiscoveryCursor()
        resetManifests() // event-scoped: re-enqueue manifests for the configured event (markers are assetId-keyed)
        marker.set(configuredEventId) // settle even when seeds is empty → the next cycle does not re-loop
        log.i { "joined $configuredEventId — seeded ${seeds.size} resource(s)" }
        return true
    }
}
