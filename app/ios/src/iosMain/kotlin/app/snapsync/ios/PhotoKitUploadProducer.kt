package app.snapsync.ios

import app.snapsync.engine.DISCOVERY_TOKEN_KEY
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.ports.LedgerStore
import app.snapsync.feature.upload.clearRequestedOffMain
import app.snapsync.feature.upload.UploadProducer
import app.snapsync.model.invocation
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUserDefaults
import platform.Photos.PHPhotoLibrary

/**
 * The OS-driven (iOS ≥26.1) tier's [UploadProducer] — the PhotoKit upload-extension registration
 * mechanism (capability `ios-photokit-upload`). Constructed **only** when that tier is selected; on iOS
 * 18–26.0 (or under the dev tier-force flag) this object never exists, so its `PHPhotoLibrary` calls
 * cannot run. That is what makes the two tiers mutually exclusive *structurally* rather than by a runtime
 * guard — and it is why the force flag can no longer enable both tiers at once (two `LedgerWriter`s over
 * one App-Group ledger would breach `sync-ledger`'s single-record-writer invariant).
 *
 * The app performs no upload, fetch, enumeration, or seed on this tier: the extension self-reconciles on
 * its next cycle, gated by its `joinedEventId` marker (`event-rejoin-reconciliation`).
 */
class PhotoKitUploadProducer(
    private val ledgerStore: LedgerStore,
    private val log: Logger,
) : UploadProducer {

    /**
     * Register the extension — a **disable→enable toggle**, not a bare enable.
     *
     * The system's upload-job configuration record is keyed by bundle id and survives app
     * delete/reinstall and reboot, so a stale record (e.g. from a prior or differently-signed build) makes
     * a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which
     * the OS never launches the extension. The leading `enable(false)` deletes the stale record so
     * `enable(true)` re-creates it cleanly — and the re-register is what reliably prompts the OS to
     * schedule `process()`. Idempotent-safe to repeat.
     *
     * This ritual is **specific to this tier**: it exists to fix an OS registration record. The app-driven
     * tier has no such record, which is why applying this shape to it — the tier-blind
     * `enableBackgroundUpload()` this producer replaces — resolved to a destructive teardown followed by a
     * no-op.
     */
    override suspend fun start() = log.invocation("photokit.start") {
        stop() // awaited: the off-main REQUESTED clear completes BEFORE the re-enable below
        setEnabled(true)
        log.i { "background-upload extension re-registered (disable→enable, cleared REQUESTED)" }
    }

    /**
     * Deregister the extension AND recover the jobs the disable wipes (capability `ios-photokit-upload`).
     * `setUploadJobExtensionEnabled(false)` deletes the OS upload-job configuration, wiping every in-flight
     * job. Two clears make that recoverable:
     *
     * - `clearRequested()` — drop the now-orphaned `REQUESTED` rows. The engine never re-issues a
     *   `REQUESTED` key and no API surfaces the vanished job, so without this they stay `REQUESTED`
     *   forever. Awaited **off-main with a bounded retry** so it completes before any re-enable (a
     *   fire-and-forget clear raced the immediate re-enable and could delete the re-enabled extension's
     *   fresh rows).
     * - reset the discovery cursor — `clearRequested` only makes the keys ABSENT; a settled cursor would
     *   scan incrementally and never re-surface them, so force a full re-enumeration next cycle.
     *
     * Both are **repairs for damage this tier's OS disable causes**, not lifecycle intent — which is why
     * they have no counterpart on the app-driven tier (whose `stop()` cancels transfers and nothing else:
     * a background `URLSession` can enumerate its tasks, so stranded rows are reconciled precisely).
     *
     * `COMPLETED` rows are untouched, so stored files never re-upload. This destroys no dedup state.
     */
    override suspend fun stop() = log.invocation("photokit.stop") {
        setEnabled(false)
        NSUserDefaults(suiteName = LEDGER_APP_GROUP).removeObjectForKey(DISCOVERY_TOKEN_KEY)
        clearRequestedOffMain({ ledgerStore.clearRequested() }, log = log) // Boolean; the seam returns Unit
        Unit
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun setEnabled(enabled: Boolean) {
        PHPhotoLibrary.sharedPhotoLibrary().setUploadJobExtensionEnabled(enabled, error = null)
    }
}
