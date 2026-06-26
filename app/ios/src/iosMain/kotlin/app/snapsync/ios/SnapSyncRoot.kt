package app.snapsync.ios

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.KeychainConfigStore
import app.snapsync.config.decodeConfigUrl
import app.snapsync.engine.DISCOVERY_TOKEN_KEY
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerWatcher
import app.snapsync.engine.iosLedgerBackend
import app.snapsync.gallery.PhotoLibraryGalleryStatus
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.status.LedgerSyncStatusSource
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults
import platform.Photos.PHAssetResourceUploadJob
import platform.Photos.PHAssetResourceUploadJobAction
import platform.Photos.PHAssetResourceUploadJobActionAcknowledge
import platform.Photos.PHAssetResourceUploadJobActionRetry
import platform.Photos.PHAssetResourceUploadJobStateCancelled
import platform.Photos.PHAssetResourceUploadJobStateFailed
import platform.Photos.PHAssetResourceUploadJobStateRegistered
import platform.Photos.PHAssetResourceUploadJobStateSucceeded
import platform.Photos.PHPhotoLibrary

/**
 * The iOS composition root (D7): a single app-lifetime singleton that assembles the real live
 * stack. It owns a `SupervisorJob` scope on the main dispatcher so the source's collector and the
 * Orbit container outlive Compose recomposition (not a `rememberCoroutineScope`, which dies with
 * the view). The app has exactly one root screen, so process-lifetime ownership is correct; the
 * Swift entry point stays untouched. Move ownership to Swift only if scene-aware lifecycle or
 * scope recreation (multi-window, reset/logout) is ever needed.
 *
 * Assembly is lazy so it runs once on first view creation: ledger backend → watcher → source ×
 * PhotoKit permission → container. `permission` and `config` are each passed as both their ports
 * (one adapter implements both). The app reads the ledger (it never constructs a `LedgerWriter` —
 * the background-upload extension is the single writer) and, on a full grant, enables that
 * extension where supported.
 */
object SnapSyncRoot {

    init {
        // Route kermit through a public NSLog writer AND a file writer. NSLog is redacted as
        // `<private>` on current iOS (dynamic format strings are private), so the file writer
        // (Documents/debug.log, pulled via `pymobiledevice3 apps pull`) is the reliable channel.
        Logger.setLogWriters(PublicNSLogWriter(), FileLogWriter())
    }

    private val log = Logger.withTag("SnapSyncRoot")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ledgerBackend: LedgerBackend by lazy { iosLedgerBackend() }

    // The live photo-library count (N), held so a re-provision can ding it to re-read.
    private val gallery: PhotoLibraryGalleryStatus by lazy { PhotoLibraryGalleryStatus() }

    val host: StatusContainerHost by lazy {
        val watcher = LedgerWatcher(ledgerBackend)
        val permission = PhotoLibraryPermission()
        val config = KeychainConfigStore()
        val syncSource = LedgerSyncStatusSource(watcher, permission, gallery, scope)
        enableBackgroundUploadOnGrant(permission)
        // `config` is passed as both ports (one Keychain adapter implements both), as `permission` is.
        StatusContainerHost(syncSource, permission, permission, config, config, scope)
    }

    /**
     * A `snapsync://` deeplink arrived (forwarded raw from the Swift entry point). Routed through
     * the container's intent so decode/validate/persist all happen in shared Kotlin.
     *
     * A **valid (re)scan re-provisions**: the ledger and discovery cursor are reset and the
     * extension is re-registered, so the (possibly new) config re-uploads the whole library from
     * scratch. We decode here only to gate the reset on a valid deeplink — the host still performs
     * the authoritative decode/validate/persist. (Resetting an already-empty ledger on the first
     * scan is a harmless no-op.)
     */
    fun onOpenUrl(url: String) {
        if (decodeConfigUrl(url) is ConfigDecodeResult.Success) {
            scope.launch { resetForReprovision() }
        }
        host.onOpenUrl(url)
    }

    /**
     * Realize [launchEnvDeeplinkApplied] once on first view creation (called from
     * [MainViewController]). Touching the `by lazy` runs the env read exactly once per process.
     */
    fun applyLaunchEnvDeeplink() {
        launchEnvDeeplinkApplied
    }

    /**
     * Dev/test trigger: if a `SNAPSYNC_DEEPLINK` process-environment variable is present, forward its
     * value through [onOpenUrl] exactly as a scanned QR would, provisioning the event headlessly over
     * USB. The variable is only injectable via a developer launch
     * (`pymobiledevice3 developer dvt launch --env …`); SpringBoard and TestFlight launches carry a
     * clean environment, so this is inert in production with no compile-time guard. Read **once per
     * process** (`by lazy`): a fresh cold launch with the variable still set re-provisions (the
     * intended per-build re-trigger); a mere view recreation within the same process does not.
     */
    private val launchEnvDeeplinkApplied: Boolean by lazy {
        val raw = NSProcessInfo.processInfo.environment["SNAPSYNC_DEEPLINK"] as? String
        if (raw != null) {
            log.i { "applying SNAPSYNC_DEEPLINK launch-env deeplink" }
            onOpenUrl(raw)
        }
        true
    }

    private suspend fun resetForReprovision() {
        ledgerBackend.clear()
        clearDiscoveryCursor()
        gallery.refresh() // new event baseline → re-read the gallery total (N)
        reRegisterExtension()
        log.i { "re-provisioned: ledger + discovery cursor reset, extension re-registered" }
    }

    /**
     * Clear the extension's persisted discovery cursor so the next cycle re-enumerates the whole
     * library. Suite/key are the shared constants the extension's `IosDiscoveryStore` writes under.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun clearDiscoveryCursor() {
        NSUserDefaults(suiteName = LEDGER_APP_GROUP).removeObjectForKey(DISCOVERY_TOKEN_KEY)
    }

    /**
     * The app's only producer-side responsibility: once photo access is full (`GRANTED`), register
     * the background-upload extension so the system can invoke its `process()`. The app performs no
     * discovery or upload itself.
     *
     * Registration is a **disable→enable toggle**, not a bare `enable(true)`. The system's
     * `AssetResourceUploadJobConfiguration` is keyed by bundle id and **persists across app
     * delete/reinstall and device reboot**; a stale one (e.g. left by a differently-signed build)
     * makes a plain `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record")
     * and the extension is then never launched. Calling `enable(false)` first tears the stale
     * record down so `enable(true)` re-creates it cleanly, matching the currently-installed
     * extension. Safe to repeat.
     */
    private fun enableBackgroundUploadOnGrant(permission: PhotoLibraryPermission) {
        scope.launch {
            permission.permission.collect { status ->
                if (status == PermissionStatus.GRANTED) reRegisterExtension()
            }
        }
    }

    /**
     * The disable→enable toggle (see above). `setUploadJobExtensionEnabled` is iOS 26.1+, but the
     * app deploys lower, so the call is guarded — on older systems the app simply runs without
     * background upload. Safe to repeat; called on a full grant and on every re-provision.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun reRegisterExtension() {
        if (!backgroundUploadSupported()) return
        val lib = PHPhotoLibrary.sharedPhotoLibrary()
        val disabled = lib.setUploadJobExtensionEnabled(false, error = null)
        val enabled = lib.setUploadJobExtensionEnabled(true, error = null)
        log.i { "background-upload extension re-registered: disabled=$disabled enabled=$enabled" }
    }

    /**
     * SPIKE — remove once the question is answered. Can the **main app process** (not just the
     * background-upload extension) enumerate the system's upload jobs via
     * `PHAssetResourceUploadJob.fetchJobsWithAction`? Called on every foreground (from the Swift
     * scene's `.active` transition).
     *
     * Strictly **read-only**: it fetches and logs counts + a state breakdown, and NEVER
     * acknowledges, retries, or otherwise mutates a job. So it cannot consume jobs the extension
     * still needs to acknowledge — the extension stays the single ledger writer. The route is
     * viable iff (a) this logs non-zero counts while uploads are in flight, and (b) the extension
     * keeps seeing/acking the same jobs afterwards (cross-check the extension's own
     * `fetch(acknowledge): N job(s)` logs and the ledger reaching COMPLETED).
     */
    @OptIn(ExperimentalForeignApi::class)
    fun probeUploadJobs() {
        if (!backgroundUploadSupported()) {
            log.i { "probeUploadJobs skipped: background-upload API unavailable on this OS" }
            return
        }
        logFetchedJobs("acknowledge", PHAssetResourceUploadJobActionAcknowledge)
        logFetchedJobs("retry", PHAssetResourceUploadJobActionRetry)
    }

    private fun logFetchedJobs(name: String, action: PHAssetResourceUploadJobAction) {
        val jobs = PHAssetResourceUploadJob.fetchJobsWithAction(action, options = null)
        var succeeded = 0
        var failed = 0
        var cancelled = 0
        var registered = 0
        var other = 0
        var index = 0uL
        while (index < jobs.count) {
            val job = jobs.objectAtIndex(index) as PHAssetResourceUploadJob
            index++
            when (job.state) {
                PHAssetResourceUploadJobStateSucceeded -> succeeded++
                PHAssetResourceUploadJobStateFailed -> failed++
                PHAssetResourceUploadJobStateCancelled -> cancelled++
                PHAssetResourceUploadJobStateRegistered -> registered++
                else -> other++
            }
        }
        log.i {
            "probeUploadJobs fetch($name) from APP: count=${jobs.count} succeeded=$succeeded " +
                "failed=$failed cancelled=$cancelled registered=$registered other=$other"
        }
    }

    /** Whether the iOS 26.1 background-upload API is present on this system. */
    @OptIn(ExperimentalForeignApi::class)
    private fun backgroundUploadSupported(): Boolean =
        NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
            cValue<NSOperatingSystemVersion> {
                majorVersion = 26
                minorVersion = 1
                patchVersion = 0
            },
        )
}
