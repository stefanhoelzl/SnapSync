package app.snapsync.ios

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.KeychainConfigStore
import app.snapsync.config.decodeConfigUrl
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerWatcher
import app.snapsync.engine.iosLedgerBackend
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
        // Route kermit through a public NSLog writer so logs are visible via `idevicesyslog`
        // un-redacted (the default os_log path drops `.info` and redacts dynamic content as
        // `<private>`).
        Logger.setLogWriters(PublicNSLogWriter())
    }

    private val log = Logger.withTag("SnapSyncRoot")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ledgerBackend: LedgerBackend by lazy { iosLedgerBackend() }

    val host: StatusContainerHost by lazy {
        val watcher = LedgerWatcher(ledgerBackend)
        val permission = PhotoLibraryPermission()
        val config = KeychainConfigStore()
        val syncSource = LedgerSyncStatusSource(watcher, permission, scope)
        enableBackgroundUploadOnGrant(permission)
        // Surface the Local Network permission now (the background extension cannot prompt). No-op
        // against a public HTTPS endpoint; needed when the upload host is a private/local address.
        primeLocalNetwork(config, log)
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

    private suspend fun resetForReprovision() {
        ledgerBackend.clear()
        clearDiscoveryCursor()
        reRegisterExtension()
        log.i { "re-provisioned: ledger + discovery cursor reset, extension re-registered" }
    }

    /**
     * Clear the extension's persisted discovery cursor so the next cycle re-enumerates the whole
     * library. The suite and key MUST match the extension's `IosDiscoveryStore`
     * (`group.app.snapsync` / `discovery.changeToken`).
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun clearDiscoveryCursor() {
        NSUserDefaults(suiteName = LEDGER_APP_GROUP).removeObjectForKey("discovery.changeToken")
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
