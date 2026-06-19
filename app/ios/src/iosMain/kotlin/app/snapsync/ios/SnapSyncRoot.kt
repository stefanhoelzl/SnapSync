package app.snapsync.ios

import app.snapsync.config.KeychainConfigStore
import app.snapsync.engine.LedgerWatcher
import app.snapsync.engine.iosLedgerBackend
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.status.LedgerSyncStatusSource
import co.touchlab.kermit.Logger
import co.touchlab.kermit.NSLogWriter
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
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
        // Route kermit through NSLog so logs are visible via `idevicesyslog` (Mac-less debugging):
        // the default os_log writer emits at `.info`, which the device syslog stream drops.
        Logger.setLogWriters(NSLogWriter())
    }

    private val log = Logger.withTag("SnapSyncRoot")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val host: StatusContainerHost by lazy {
        val watcher = LedgerWatcher(iosLedgerBackend())
        val permission = PhotoLibraryPermission()
        val config = KeychainConfigStore()
        val syncSource = LedgerSyncStatusSource(watcher, permission, scope)
        enableBackgroundUploadOnGrant(permission)
        // `config` is passed as both ports (one Keychain adapter implements both), as `permission` is.
        StatusContainerHost(syncSource, permission, permission, config, config, scope)
    }

    /**
     * A `snapsync://` deeplink arrived (forwarded raw from the Swift entry point). Routed through
     * the container's intent so decode/validate/persist all happen in shared Kotlin.
     */
    fun onOpenUrl(url: String) {
        host.onOpenUrl(url)
    }

    /**
     * The app's only producer-side responsibility: once photo access is full (`GRANTED`), enable
     * the background-upload extension so the system can invoke its `process()`. Idempotent-safe to
     * repeat (re-grant, foreground refresh). The app performs no discovery or upload itself.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun enableBackgroundUploadOnGrant(permission: PhotoLibraryPermission) {
        scope.launch {
            permission.permission.collect { status ->
                // `setUploadJobExtensionEnabled` is iOS 26.1+, but the app deploys lower. Guard the
                // call so it never traps on older systems — there the app simply runs without
                // background upload.
                if (status == PermissionStatus.GRANTED && backgroundUploadSupported()) {
                    val enabled = PHPhotoLibrary.sharedPhotoLibrary()
                        .setUploadJobExtensionEnabled(true, error = null)
                    log.i { "background-upload extension enabled=$enabled" }
                }
            }
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
