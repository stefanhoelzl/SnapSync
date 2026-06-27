package app.snapsync.ios

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.KeychainConfigStore
import app.snapsync.config.decodeConfigUrl
import app.snapsync.engine.DISCOVERY_TOKEN_KEY
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerWatcher
import app.snapsync.engine.iosLedgerBackend
import app.snapsync.eventstatus.MutableEventStatusSource
import app.snapsync.gallery.PhotoLibraryGalleryStatus
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.rejoin.HttpEventFilesSource
import app.snapsync.rejoin.JoinEvent
import app.snapsync.rejoin.LeaveEvent
import app.snapsync.rejoin.darwinHttpClient
import app.snapsync.status.LedgerSyncStatusSource
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

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

    // The event config seam/store (one Keychain adapter is both), hoisted so the join can read the
    // current event id for switch detection and the gate.
    private val config: KeychainConfigStore by lazy { KeychainConfigStore() }

    // The re-join status the JoinEvent drives and the container reads (same instance).
    private val eventStatus = MutableEventStatusSource()

    // The re-join reconciliation use-case: fetch the event's stored files (Darwin HTTPS), enumerate
    // the library via the shared gallery derivation, and seed already-stored photos before enabling
    // the producer. The host is the same compile-time base baked into the app Info.plist.
    private val joinEvent: JoinEvent by lazy {
        val host = NSBundle.mainBundle.objectForInfoDictionaryKey("BackgroundUploadURLBase") as? String ?: ""
        JoinEvent(
            files = HttpEventFilesSource(darwinHttpClient(), host),
            enumerator = PhotoLibraryResourceEnumerator(),
            ledger = ledgerBackend,
            config = config,
            status = eventStatus,
            clearDiscoveryCursor = { clearDiscoveryCursor() },
        )
    }

    // The leave use-case: the local-only inverse of the join. Disables the producer first (no
    // concurrent ledger writer), then resets the ledger, clears the discovery cursor, clears the
    // Keychain config, and returns the status to Idle. Platform effects are the same ones the enable
    // gate uses, injected as lambdas so the use-case stays pure/tested.
    private val leaveEvent: LeaveEvent by lazy {
        LeaveEvent(
            config = config,
            ledger = ledgerBackend,
            status = eventStatus,
            disableExtension = { setUploadExtensionEnabled(false) },
            clearDiscoveryCursor = { clearDiscoveryCursor() },
        )
    }

    // The platform foreground signal driving the observed-completions poll. Seeded false; the Swift
    // scene flips it via onForeground()/onBackground() on its scene-phase transitions.
    private val foreground = MutableStateFlow(false)

    val host: StatusContainerHost by lazy {
        val watcher = LedgerWatcher(ledgerBackend)
        val permission = PhotoLibraryPermission()
        // The read-only PhotoKit upload-job reader: succeeded-but-unacknowledged jobs the ledger does
        // not yet know about. The overlay in the status source projects them onto live progress.
        val observed = IosObservedCompletionsSource(log) { backgroundUploadSupported() }
        val syncSource = LedgerSyncStatusSource(watcher, permission, gallery, observed, scope)
        enableBackgroundUploadOnGrant(permission)
        // `config` is passed as both ports (one Keychain adapter implements both), as `permission` is;
        // `eventStatus` is the same instance the join drives, so the screen shows Joining/JoinFailed.
        StatusContainerHost(
            syncSource, permission, permission, config, config, scope,
            observed = observed, foreground = foreground, eventStatusSource = eventStatus,
            leave = leaveEvent::leave,
            // Fire-and-forget share of the invite deeplink (the host owns the URL). Wiring-only:
            // present the system share sheet over the current top view controller.
            share = { url -> presentShareSheet(url) },
        )
    }

    /**
     * The SwiftUI scene's foreground transitions (forwarded from the `@main` scene's scenePhase).
     * They gate the observed-completions poll: foreground + pending work → refresh on an interval.
     * Touching [host] ensures the stack is assembled before the first transition arrives.
     */
    fun onForeground() {
        host
        foreground.value = true
    }

    fun onBackground() {
        foreground.value = false
    }

    /**
     * A `snapsync://` deeplink arrived (forwarded raw from the Swift entry point). A **valid scan
     * (re)provisions and reconciles** (no longer a forced re-upload): an event **switch** resets the
     * ledger, the same event is a no-op, then the join gate seeds already-stored photos before the
     * producer is (re)enabled. We decode here to capture the previous event id *before* saving the new
     * one (switch detection); an invalid link flashes the transient error via the container.
     */
    fun onOpenUrl(url: String) {
        when (val decoded = decodeConfigUrl(url)) {
            is ConfigDecodeResult.Success -> scope.launch {
                val previous = config.config.value?.eventId
                joinEvent.onProvision(previous, decoded.payload.eventId) // switch reset (before save)
                config.save(decoded.payload) // persist; the container's ConfigSource is this instance
                gallery.refresh() // (re)joined event → re-read the gallery total (N)
                reconcileThenEnable()
            }
            is ConfigDecodeResult.Failure -> host.onOpenUrl(url) // flashes the invalid-link error
        }
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

    /**
     * The enable gate (event-rejoin-reconciliation): disable the extension (no concurrent writer
     * during a seed), run the join — which, when needed, seeds already-stored photos as `COMPLETED`
     * and clears the discovery cursor — then enable the extension **only if** the join is satisfied
     * (joined, or the ledger already holds rows). On a failed list fetch the producer is left disabled
     * and the user re-scans the QR to retry. The disable→enable also clears any stale 3202 config
     * record (as the old re-register toggle did). Idempotent; called on a full grant and on every
     * (re)provision.
     */
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun reconcileThenEnable() {
        if (!backgroundUploadSupported()) return
        setUploadExtensionEnabled(false)
        val ok = joinEvent.ensureJoined()
        if (ok) {
            setUploadExtensionEnabled(true)
            log.i { "background-upload extension enabled (join satisfied)" }
        } else {
            log.i { "join not satisfied — extension left disabled (user re-scans to retry)" }
        }
    }

    /**
     * Toggle the background-upload extension registration, guarded so the iOS 26.1 call never traps on
     * lower systems. Shared by the enable gate ([reconcileThenEnable]) and the leave use-case's
     * disable lambda, so both go through one guarded path.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun setUploadExtensionEnabled(enabled: Boolean) {
        if (!backgroundUploadSupported()) return
        PHPhotoLibrary.sharedPhotoLibrary().setUploadJobExtensionEnabled(enabled, error = null)
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
     * Present the system share sheet (`UIActivityViewController`) carrying the invite deeplink, from
     * the current top-most view controller. Wiring-only and fire-and-forget — no completion handler;
     * the host already holds the URL and `UiState` is unaffected. iPhone-only/portrait, so no popover
     * source is needed. Runs on the main dispatcher (the Orbit intent's context).
     */
    private fun presentShareSheet(text: String) {
        val activity = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        var presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (presenter?.presentedViewController != null) {
            presenter = presenter.presentedViewController
        }
        presenter?.presentViewController(activity, animated = true, completion = null)
    }

    /**
     * The app's only producer-side responsibility: once photo access is full (`GRANTED`), run the
     * reconcile gate and (if satisfied) register the background-upload extension so the system can
     * invoke its `process()`. The app performs no upload itself; the one-time join enumeration is the
     * only producer-adjacent work it does. Re-runs on each grant/foreground transition to GRANTED.
     */
    private fun enableBackgroundUploadOnGrant(permission: PhotoLibraryPermission) {
        scope.launch {
            permission.permission.collect { status ->
                if (status == PermissionStatus.GRANTED) reconcileThenEnable()
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
