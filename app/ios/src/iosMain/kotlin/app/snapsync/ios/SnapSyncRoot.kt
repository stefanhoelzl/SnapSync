package app.snapsync.ios

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.EventConfigPayload
import app.snapsync.config.KeychainConfigStore
import app.snapsync.config.decodeConfigUrl
import app.snapsync.eventcreation.CreateEvent
import app.snapsync.eventcreation.EventCreator
import app.snapsync.eventcreation.HttpEventCreationClient
import app.snapsync.eventcreation.MutableCreationStatusSource
import app.snapsync.gallery.IosManifestStore
import app.snapsync.gallery.PhotoLibraryGalleryStatus
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.rejoin.HttpEventFilesSource
import app.snapsync.rejoin.LeaveEvent
import app.snapsync.rejoin.darwinHttpClient
import app.snapsync.status.DirectoryPendingManifestsSource
import app.snapsync.status.FilesCompletedAssetsSource
import app.snapsync.status.ListingSyncStatusSource
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The iOS composition root (D7): a single app-lifetime singleton that assembles the real live
 * stack. It owns a `SupervisorJob` scope on the main dispatcher so the source's collector and the
 * Orbit container outlive Compose recomposition (not a `rememberCoroutineScope`, which dies with
 * the view). The app has exactly one root screen, so process-lifetime ownership is correct; the
 * Swift entry point stays untouched. Move ownership to Swift only if scene-aware lifecycle or
 * scope recreation (multi-window, reset/logout) is ever needed.
 *
 * Assembly is lazy so it runs once on first view creation: the storage-truth status sources
 * (completeness listing + on-disk manifests) × the gallery total × PhotoKit permission → the
 * listing-backed source → container. `permission` and `config` are each passed as both their ports
 * (one adapter implements both). Status derives from **storage truth, not the ledger** — the app no
 * longer reads the ledger (the background-upload extension is its sole, private owner) — and, on a
 * full grant, enables that extension where supported.
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

    // The live photo-library count (N), held so a re-provision can ding it to re-read.
    private val gallery: PhotoLibraryGalleryStatus by lazy { PhotoLibraryGalleryStatus() }

    // The event config seam/store (one Keychain adapter is both), hoisted so a (re)provision can read
    // the current event id and the leave use-case can clear it.
    private val config: KeychainConfigStore by lazy { KeychainConfigStore() }

    // The photo-library permission adapter, hoisted so the grant collector and a (re)provision share one
    // instance (both enable the extension; a provision must re-enable a producer a prior leave disabled).
    private val permission: PhotoLibraryPermission by lazy { PhotoLibraryPermission() }

    // The shared App-Group manifest store, hoisted so the background-upload controller (writes DONE,
    // re-enqueues) and the in-flight status reader (lists/prunes PENDING markers) see one instance.
    private val manifestStore: IosManifestStore by lazy { IosManifestStore() }

    // Status from storage truth (capability `sync-status`), no ledger read:
    //   completed ← the event's completeness listing (GET /event/<id>/files, Darwin HTTPS);
    //   in-flight ← the on-disk PENDING manifests (with a complete-asset prune backstop).
    // Both refresh on foreground entry and on each manifest URLSession completion (event-driven; no
    // polling timer). The host is the same compile-time base the rejoin/upload clients use.
    private val completedAssets: FilesCompletedAssetsSource by lazy {
        val host = NSBundle.mainBundle.objectForInfoDictionaryKey("BackgroundUploadURLBase") as? String ?: ""
        FilesCompletedAssetsSource(HttpEventFilesSource(darwinHttpClient(), host)) { config.config.value?.eventId }
    }
    private val pendingManifests: DirectoryPendingManifestsSource by lazy {
        DirectoryPendingManifestsSource(IosManifestDirectory(manifestStore), completedAssets)
    }

    // The app end of the manifest background URLSession (capability `asset-manifest`): the system
    // relaunches the app to finish uploads the extension started; this controller adopts that session,
    // marks each manifest DONE on success, and re-enqueues on failure. On a successful landing it
    // also re-LISTs + re-reads the in-flight manifests so status stays live. The host is the same base.
    private val manifestController: ManifestUploadController by lazy {
        val host = NSBundle.mainBundle.objectForInfoDictionaryKey("BackgroundUploadURLBase") as? String ?: ""
        ManifestUploadController(
            store = manifestStore,
            host = host,
            eventIdProvider = { config.config.value?.eventId },
            onManifestUploaded = { scope.launch { refreshStatusSources() } },
            log = log,
        )
    }

    // The leave use-case: the local-only inverse of a join. Disables the producer, then clears the
    // Keychain config — only. It constructs no ledger type; the extension resets its own private ledger,
    // cursor, and joinedEventId marker on its next cycle once the configured event no longer matches.
    private val leaveEvent: LeaveEvent by lazy {
        LeaveEvent(
            config = config,
            disableExtension = { setUploadExtensionEnabled(false) },
        )
    }

    // The create-event status the use-case drives and the container reads (same instance).
    private val creationStatus = MutableCreationStatusSource()

    // The create-event use-case: mint via the deployed backend (Darwin HTTPS, host from Info.plist —
    // the same base the rejoin client uses), then provision the returned event id through the very
    // same path a scanned QR takes ([provisionEvent]).
    private val eventCreator: EventCreator by lazy {
        val host = NSBundle.mainBundle.objectForInfoDictionaryKey("BackgroundUploadURLBase") as? String ?: ""
        CreateEvent(
            client = HttpEventCreationClient(darwinHttpClient(), host),
            status = creationStatus,
            provision = { eventId -> provisionEvent(eventId) },
            scope = scope,
        )
    }

    val host: StatusContainerHost by lazy {
        // The listing-backed source: completeness listing × in-flight manifests × permission × the
        // live gallery total, minted into snapshots. No ledger read, no observed-completions overlay.
        val syncSource = ListingSyncStatusSource(completedAssets, pendingManifests, permission, gallery, scope)
        enableBackgroundUploadOnGrant()
        // `config` is passed as both ports (one Keychain adapter implements both), as `permission` is.
        // No EventStatus source: status is read from the listing; the extension owns reconciliation.
        StatusContainerHost(
            syncSource, permission, permission, config, config, scope,
            creationStatusSource = creationStatus, creator = eventCreator,
            leave = leaveEvent::leave,
            // Fire-and-forget share of the invite deeplink (the host owns the URL). Wiring-only:
            // present the system share sheet over the current top view controller.
            share = { url -> presentShareSheet(url) },
        )
    }

    /**
     * The SwiftUI scene's foreground transition (forwarded from the `@main` scene's scenePhase):
     * re-LIST the completeness listing and re-read the in-flight manifests so status reflects any
     * completions that landed while backgrounded (capability `sync-status` liveness). Touching [host]
     * ensures the stack is assembled before the first transition arrives.
     */
    fun onForeground() {
        host
        scope.launch { refreshStatusSources() }
    }

    /** No-op: status liveness is event-driven (foreground entry + manifest completion), never polled. */
    fun onBackground() = Unit

    /** Re-read both storage-truth status sources (completeness listing + on-disk in-flight manifests). */
    private suspend fun refreshStatusSources() {
        completedAssets.refresh()
        pendingManifests.refresh()
    }

    /**
     * The system relaunched the app to finish background `URLSession` events (the extension's manifest
     * uploads). Forwarded raw from the Swift app delegate's `handleEventsForBackgroundURLSession`:
     * adopt the session so completions are processed, and the OS [completionHandler] is invoked once
     * the session finishes delivering them.
     */
    fun handleBackgroundUrlSession(identifier: String, completionHandler: () -> Unit) {
        manifestController.handleEvents(identifier, completionHandler)
    }

    /**
     * A `snapsync://` deeplink arrived (forwarded raw from the Swift entry point). A **valid scan
     * (re)provisions**: the config is persisted and the producer (re)enabled — the extension itself
     * reconciles against storage on its next cycle (an event switch is a `joinedEventId` marker
     * mismatch it resets and re-seeds; the same event is a no-op). An invalid link flashes the
     * transient error via the container.
     */
    fun onOpenUrl(url: String) {
        when (val decoded = decodeConfigUrl(url)) {
            is ConfigDecodeResult.Success -> scope.launch { provisionEvent(decoded.payload.eventId) }
            is ConfigDecodeResult.Failure -> host.onOpenUrl(url) // flashes the invalid-link error
        }
    }

    /**
     * Provision an event id — the shared path for both a scanned/typed deeplink and a freshly created
     * event. Persists the config (the container's `ConfigSource` is this instance), re-reads the gallery
     * total and the storage-truth status sources (the new event has its own completeness listing), and
     * re-enables the producer if access is granted. The app runs no join, fetch, or seed — the extension
     * self-reconciles, gated by its `joinedEventId` marker. Re-enabling matters because a prior leave
     * disabled the producer and the grant collector does not re-fire while permission is unchanged.
     */
    private suspend fun provisionEvent(eventId: String) {
        config.save(EventConfigPayload(eventId)) // persist; the container's ConfigSource is this instance
        gallery.refresh() // (re)joined event → re-read the gallery total (N)
        refreshStatusSources() // (re)joined event → re-LIST completeness + re-read in-flight manifests
        if (permission.permission.value == PermissionStatus.GRANTED) enableBackgroundUpload()
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
     * Enable the background-upload extension (idempotent), guarded so the iOS 26.1 call never traps on
     * lower systems. The app runs no join, fetch, or seed — reconciliation lives inside the extension,
     * gated by its `joinedEventId` marker (see `event-rejoin-reconciliation`); the extension
     * self-reconciles on its next cycle. Called on a full grant and on every (re)provision.
     *
     * Registration is a **disable→enable toggle**, not a bare enable (`ios-background-upload` spec): the
     * system's upload-job configuration record is keyed by bundle id and persists across app
     * delete/reinstall and reboot, so a stale record (e.g. from a prior or differently-signed build)
     * makes a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"),
     * after which the OS never launches the extension. The leading `enable(false)` deletes the stale
     * record so `enable(true)` re-creates it cleanly for the currently-installed extension — and the
     * re-register is what reliably prompts the OS to schedule `process()`. Idempotent-safe to repeat.
     */
    private fun enableBackgroundUpload() {
        setUploadExtensionEnabled(false)
        setUploadExtensionEnabled(true)
        log.i { "background-upload extension re-registered (disable→enable)" }
    }

    /**
     * Toggle the background-upload extension registration, guarded so the iOS 26.1 call never traps on
     * lower systems. Shared by [enableBackgroundUpload] and the leave use-case's disable lambda, so both
     * go through one guarded path.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun setUploadExtensionEnabled(enabled: Boolean) {
        if (!backgroundUploadSupported()) return
        PHPhotoLibrary.sharedPhotoLibrary().setUploadJobExtensionEnabled(enabled, error = null)
    }

    /**
     * Present the system share sheet (`UIActivityViewController`) carrying the invite deeplink, from
     * the current top-most view controller. Wiring-only and fire-and-forget — no completion handler;
     * the host already holds the URL and `UiState` is unaffected. iPhone-only/portrait, so no popover
     * source is needed.
     *
     * Marshalled onto the **main queue**: the container invokes `share` from an Orbit intent, which
     * runs on `Dispatchers.Default` (a background thread), and `UIActivityViewController` presentation
     * asserts the main queue (`dispatch_assert_queue`) — presenting off-main traps (SIGTRAP).
     */
    private fun presentShareSheet(text: String) {
        dispatch_async(dispatch_get_main_queue()) {
            val activity = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
            var presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (presenter?.presentedViewController != null) {
                presenter = presenter.presentedViewController
            }
            presenter?.presentViewController(activity, animated = true, completion = null)
        }
    }

    /**
     * The app's only producer-side responsibility: once photo access is full (`GRANTED`), register the
     * background-upload extension so the system can invoke its `process()`. The app performs no upload,
     * fetch, enumeration, or seed — the extension self-reconciles on its next cycle. Re-runs on each
     * transition to GRANTED; the enable call is idempotent-safe to repeat.
     */
    private fun enableBackgroundUploadOnGrant() {
        scope.launch {
            permission.permission.collect { status ->
                if (status == PermissionStatus.GRANTED) enableBackgroundUpload()
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
