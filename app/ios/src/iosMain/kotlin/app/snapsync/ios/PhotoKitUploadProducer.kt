package app.snapsync.ios

import app.snapsync.engine.DISCOVERY_TOKEN_KEY
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.ports.LedgerStore
import app.snapsync.feature.upload.clearRequestedOffMain
import app.snapsync.feature.upload.UploadProducer
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSUserDefaults
import app.snapsync.model.registrationOutcome
import platform.Foundation.NSError
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

    /**
     * Change the registration, and **report a failure instead of discarding it**.
     *
     * `setUploadJobExtensionEnabled` returns a `Boolean` and takes an `NSError**`, and this call site
     * discarded both until now. That mattered because the failure it hid is invisible and terminal: if
     * enabling fails, the extension is never registered, the OS never launches it, no cycle ever runs, and
     * the screen sits at "Synchronization pending…" forever with nothing in the log, on the screen, or in
     * crash reporting to say why.
     *
     * One helper for both halves, deliberately: this serves `start()` and `stop()`, and checking one but
     * not the other would be a blind spot chosen on purpose.
     *
     * **`PHPhotosErrorIdentifierNotFound` (3201) on a DISABLE is not a failure.** `start()` is a
     * disable→enable ritual, so its leading disable runs against no configuration record on any clean
     * device — measured twice on an SE2 (iOS 26.6), reproducing as `returned=false` with that code. Raising
     * on it would put a reporting event on the first join of every fresh install, burying the real signal in
     * noise this very requirement created.
     *
     * The disable's return is also **evidence**, not just an error check: a disable that FINDS a record
     * returns `true` with no error, so the write distinguishes "there was a registration" from "there was
     * not" as a side effect of doing its job — which the read-back cannot reliably do, being grant-dependent.
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun setEnabled(enabled: Boolean) = memScoped {
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val ok = PHPhotoLibrary.sharedPhotoLibrary()
            .setUploadJobExtensionEnabled(enabled, error = errorVar.ptr)
        val error = errorVar.value
        // Every branch is the tested classifier's; this reports the platform's raw facts and renders the
        // answer. `Error` severity is what carries a failure to crash reporting.
        val outcome = registrationOutcome(
            enabling = enabled,
            ok = ok,
            errorDomain = error?.domain,
            errorCode = error?.code,
        )
        // No branch: the outcome carries Kermit's own severity, so this renders without deciding. An
        // `Error` here is what `crash-reporting` carries onward as field telemetry.
        log.log(outcome.severity, log.tag, null, outcome.message)
    }
}
