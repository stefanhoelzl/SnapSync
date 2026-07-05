package app.snapsync.ios

import app.snapsync.config.ConfigDecodeResult
import app.snapsync.config.EventConfig
import app.snapsync.config.KeychainConfigStore
import app.snapsync.config.decodeConfigUrl
import app.snapsync.eventcreation.CreateEvent
import app.snapsync.eventcreation.EventCreator
import app.snapsync.eventcreation.HttpEventCreationClient
import app.snapsync.eventcreation.HttpEventMetadataSource
import app.snapsync.eventcreation.MutableCreationStatusSource
import app.snapsync.deviceid.KeychainDeviceIdentity
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.download.DownloadPushReceiver
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.push.PushReceiver
import app.snapsync.push.PushRegistration
import app.snapsync.push.PushTokenSource
import app.snapsync.rejoin.LeaveEvent
import app.snapsync.rejoin.clearRequestedOffMain
import app.snapsync.rejoin.darwinHttpClient
import app.snapsync.download.DownloadController
import app.snapsync.download.HttpEventUnionSource
import app.snapsync.download.IosPhotoDownloadJobs
import app.snapsync.download.IosPhotoLibraryImporter
import app.snapsync.download.StoreDownloadStatusSource
import app.snapsync.downloadstore.SqlDelightDownloadStore
import app.snapsync.downloadstore.iosDownloadStore
import platform.Foundation.NSFileManager
import app.snapsync.engine.DISCOVERY_TOKEN_KEY
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.iosLedgerBackend
import app.snapsync.status.LedgerBackedSyncStatusSource
import app.snapsync.status.LedgerCounts
import app.snapsync.status.OwnDeviceGalleryStatusSource
import app.snapsync.status.ReadingLedgerCountsSource
import app.snapsync.upload.UPLOAD_LIVENESS_DARWIN_NAME
import co.touchlab.kermit.Logger
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.cValue
import kotlinx.cinterop.staticCFunction
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFNotificationCenterAddObserver
import platform.CoreFoundation.CFNotificationCenterGetDarwinNotifyCenter
import platform.CoreFoundation.CFNotificationCenterRef
import platform.CoreFoundation.CFNotificationCenterRemoveEveryObserver
import platform.CoreFoundation.CFNotificationName
import platform.CoreFoundation.CFNotificationSuspensionBehaviorDeliverImmediately
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSBundle
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults
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

    /** BGTaskScheduler identifier for the download import-tail backstop — MUST match the Swift host's
     * `register(forTaskWithIdentifier:)` and the Info.plist `BGTaskSchedulerPermittedIdentifiers`. */
    const val DOWNLOAD_BACKSTOP_TASK_ID: String = "app.snapsync.download.backstop"

    // The event config seam/store (one Keychain adapter is both), hoisted so a (re)provision can read
    // the current event id and the leave use-case can clear it.
    private val config: KeychainConfigStore by lazy { KeychainConfigStore() }

    // The photo-library permission adapter, hoisted so the grant collector and a (re)provision share one
    // instance (both enable the extension; a provision must re-enable a producer a prior leave disabled).
    private val permission: PhotoLibraryPermission by lazy { PhotoLibraryPermission() }

    // The stable per-install device id (shared Keychain — the SAME item the extension reads): the
    // `/devices/<deviceId>/files/` partition the app's status lists. (Finishes wiring `device-identity` into
    // both roots.)
    private val deviceId: String by lazy { KeychainDeviceIdentity().deviceId() }

    // The own-device upload TOTAL N (capability `sync-status`): gallery enumeration minus downloaded
    // foreign photos (suppressed from upload — they live in the library but must not peg progress below
    // 100%, capability `photo-download`). Enumeration-only — no storage LIST (completeness now comes
    // from the ledger, below). Refreshes on foreground entry.
    private val gallery: OwnDeviceGalleryStatusSource by lazy {
        OwnDeviceGalleryStatusSource(
            PhotoLibraryResourceEnumerator(),
            suppressedLocalIds = { downloadStore.suppressedLocalIds() },
        )
    }

    // The app-side handle on the extension's shared App-Group ledger, used for two narrow things only:
    // a READ-ONLY aggregates read (`completed`/`pending`, below) and a reset-family `clearRequested()`
    // on extension disable (recover jobs the disable wiped, capability `ios-background-upload`). No
    // per-key record writes and no `LedgerWriter` — the extension stays the sole record writer. WAL
    // permits the concurrent cross-process read.
    private val ledgerBackend: LedgerBackend by lazy { iosLedgerBackend() }

    // Own-device completeness AND in-flight, both from one consistent ledger `aggregates()` read
    // (capability `sync-status`). Read-only; on any failure the last good counts are retained (never
    // regressed to 0). Refreshed on foreground entry AND on the extension's cross-process liveness
    // notification (below).
    private val ledgerCounts: ReadingLedgerCountsSource by lazy {
        ReadingLedgerCountsSource {
            ledgerBackend.aggregates().let { LedgerCounts(completed = it.completed, pending = it.pending) }
        }
    }

    // --- Photo download / import (capability `photo-download`) ---
    // The app-written download store (idempotency + per-resource staging + the createdLocalId the
    // extension reads as its suppression set). Concrete type so the importer can write createdLocalId
    // synchronously from inside a PhotoKit change block.
    private val downloadStore: SqlDelightDownloadStore by lazy { iosDownloadStore() }

    // Background-URLSession byte transfers (discretionary/Wi-Fi) → durable App-Group staging.
    private val downloadJobs: IosPhotoDownloadJobs by lazy {
        val container = NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)?.path
            ?: error("App Group container '$LEDGER_APP_GROUP' unavailable")
        IosPhotoDownloadJobs(scope, "$container/download-staging")
    }

    // The orchestrator: union → foreign selection → download → full-fidelity import → suppression.
    private val downloadController: DownloadController by lazy {
        val uploadHost = NSBundle.mainBundle.objectForInfoDictionaryKey("BackgroundUploadURLBase") as? String ?: ""
        val controller = DownloadController(
            union = HttpEventUnionSource(darwinHttpClient(), uploadHost),
            store = downloadStore,
            jobs = downloadJobs,
            importer = IosPhotoLibraryImporter(
                recordCreatedLocalId = { ref, id -> downloadStore.recordCreatedLocalId(ref, id) },
            ),
            myDeviceId = deviceId,
        )
        // Deliver each staged resource back to the controller off the URLSession delegate thread.
        downloadJobs.onStaged = { ref, key, path -> scope.launch { controller.onResourceStaged(ref, key, path) } }
        controller
    }

    // Download progress for the joined-layer "downloaded X of Y" line (capability `photo-download`),
    // read from the store. Refreshed on foreground entry alongside the upload status.
    private val downloadStatusSource: StoreDownloadStatusSource by lazy { StoreDownloadStatusSource(downloadStore) }

    // The leave use-case: the local-only inverse of a join. Disables the producer, then clears the
    // Keychain config — only. It constructs no ledger type; the extension resets its own private ledger,
    // cursor, and joinedEventId marker on its next cycle once the configured event no longer matches.
    private val leaveEvent: LeaveEvent by lazy {
        LeaveEvent(
            config = config,
            disableExtension = { disableExtension() },
        )
    }

    // The create-event status the use-case drives and the container reads (same instance).
    private val creationStatus = MutableCreationStatusSource()

    // The create-event use-case: mint via the deployed backend (Darwin HTTPS, host from Info.plist —
    // the same base the rejoin client uses), then provision the returned event id through the very
    // same path a scanned QR takes ([provisionEvent]).
    // The device-facing backend host (baked at compile time); shared by the create client and the
    // event-metadata (name) fetch.
    private val backendHost: String by lazy {
        NSBundle.mainBundle.objectForInfoDictionaryKey("BackgroundUploadURLBase") as? String ?: ""
    }

    // Fetches the event name by id for the scan path (create already has the name). Best-effort.
    private val metadataSource: HttpEventMetadataSource by lazy {
        HttpEventMetadataSource(darwinHttpClient(), backendHost)
    }

    // --- Push notifications (capability `push-registration`) ---
    // The compile-time APNs environment (Config.xcconfig → Info.plist `APNS_ENV`): `sandbox` for
    // dev/sideloaded builds, `production` for TestFlight/App Store. The token itself is OS-delivered
    // (the Swift AppDelegate forwards it via [onPushToken]); a rotation re-registers.
    private val pushTokenSource: PushTokenSource by lazy {
        val env = NSBundle.mainBundle.objectForInfoDictionaryKey("APNS_ENV") as? String ?: "sandbox"
        PushTokenSource(env)
    }

    // Registers the device APNs token with the backend (PUT devices/<id>/config) over the shared Darwin
    // client — on launch delivery and each rotation. Best-effort: a failed write is absorbed and retried
    // on the next token, never blocking join/upload/download. The collector is launched from [host].
    private val pushRegistration: PushRegistration by lazy {
        PushRegistration(KtorPushHttpClient(darwinHttpClient()), backendHost, deviceId)
    }

    // The silent-push receiver (capability `photo-download`): on a push for the ACTIVE event it runs
    // download discovery; a push for any other event (e.g. a locally-left event whose backend membership
    // persists) is a no-op. The active-event guard reads the current config eventId.
    private val pushReceiver: PushReceiver by lazy {
        DownloadPushReceiver(
            activeEventId = { config.config.value?.eventId },
            controller = downloadController,
        )
    }

    private val eventCreator: EventCreator by lazy {
        CreateEvent(
            client = HttpEventCreationClient(darwinHttpClient(), backendHost),
            status = creationStatus,
            // Create has the name from POST /event — provision it directly, no metadata fetch.
            provision = { eventId, name -> provisionEvent(eventId, name) },
            scope = scope,
        )
    }

    val host: StatusContainerHost by lazy {
        // The own-device source: ledger completeness + in-flight (one aggregates() read) × permission ×
        // the live own-device gallery total, minted into snapshots. Ledger-sourced, no storage LIST for
        // upload status (design.md §2.4); safe under no-deletion-during-an-active-event.
        val syncSource = LedgerBackedSyncStatusSource(ledgerCounts, permission, gallery, scope)
        enableBackgroundUploadOnGrant()
        // Start registering the APNs token: the collector reacts to each token the AppDelegate delivers
        // (StateFlow-retained, so a token delivered before this launches is still registered).
        scope.launch { pushRegistration.run(pushTokenSource) }
        // `config` is passed as both ports (one Keychain adapter implements both), as `permission` is.
        // No EventStatus source: status is read from the listing; the extension owns reconciliation.
        StatusContainerHost(
            syncSource, permission, permission, config, config, scope,
            creationStatusSource = creationStatus, creator = eventCreator,
            // Leave is local-only: also cancel in-flight downloads and drop non-terminal rows (imported
            // photos stay; suppression rows are permanent). Then the existing leave clears config/producer.
            leave = { downloadController.onLeaveOrSwitch(); leaveEvent.leave() },
            // Fire-and-forget share of the invite deeplink (the host owns the URL). Wiring-only:
            // present the system share sheet over the current top view controller.
            share = { url -> presentShareSheet(url) },
            // A scanned QR provisions through the same path as create (switch-reset, save, enable,
            // reconcile) — with a best-effort name fetch, since the QR carries only the eventId.
            provisionScanned = { eventId -> provisionEvent(eventId, name = null) },
            downloadSource = downloadStatusSource,
        )
    }

    /**
     * The SwiftUI scene's foreground transition (forwarded from the `@main` scene's scenePhase):
     * re-read the per-device file listing (completeness) and the ledger's in-flight count so status
     * reflects any uploads that progressed while backgrounded (capability `sync-status` liveness).
     * Touching [host] ensures the stack is assembled before the first transition arrives.
     */
    fun onForeground() {
        host
        // Listen for the extension's cross-process liveness ding while foreground, so upload status moves
        // live as the extension records completions/new jobs (design.md §2.3). Foreground-only: a
        // suspended app cannot act on the post, and this foreground entry already re-reads below.
        registerLivenessObserver()
        // App-driven upload tier (iOS 18–26.0): foreground entry pumps an upload cycle (completions then
        // keep it draining while the app is open). No-op on ≥26.1 (the OS drives the extension).
        log.i { "onForeground: useAppDrivenUpload=$useAppDrivenUpload (force=$forceUrlSessionUpload, osSupported=${backgroundUploadSupported()})" }
        if (useAppDrivenUpload) urlSessionUpload.onForeground()
        scope.launch { refreshStatusSources() }
        // Foreground-only discovery (capability `photo-download`): pick up foreign photos others added
        // since the last read, and import anything already staged. No background poll.
        scope.launch { config.config.value?.eventId?.let { downloadController.reconcile(it) } }
        // Keep the event title current (fills a name a scan couldn't fetch while offline).
        scope.launch { config.config.value?.eventId?.let { fetchAndStoreName(it) } }
    }

    /**
     * On backgrounding, queue the download import-tail backstop so any staged-but-unimported foreign
     * assets get imported at the next idle/charging window even if no further download wakes the app
     * (capability `photo-download`, 5.4). Status liveness itself stays event-driven (foreground entry).
     */
    fun onBackground() {
        unregisterLivenessObserver()
        scheduleDownloadBackstop()
    }

    // --- Extension → app cross-process liveness ding (capability `sync-status` / `ios-app-shell`) ---
    // A stable observer token (the object itself); the callback ignores it and pokes SnapSyncRoot
    // directly (it is a singleton object). Never disposed — process-lifetime.
    @OptIn(ExperimentalForeignApi::class)
    private val livenessObserverToken: COpaquePointer by lazy { StableRef.create(this).asCPointer() }

    // The Darwin notification name, created once (a single process-lifetime CFString for a constant).
    @OptIn(ExperimentalForeignApi::class)
    private val livenessName: CFStringRef? by lazy {
        CFStringCreateWithCString(null, UPLOAD_LIVENESS_DARWIN_NAME, kCFStringEncodingUTF8)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun registerLivenessObserver() {
        val center = CFNotificationCenterGetDarwinNotifyCenter()
        // Defensive: drop any prior registration for this token before (re)adding, so repeated
        // foregrounds never stack observers.
        CFNotificationCenterRemoveEveryObserver(center, livenessObserverToken)
        CFNotificationCenterAddObserver(
            center,
            livenessObserverToken,
            staticCFunction(::uploadLivenessCallback),
            livenessName,
            null,
            CFNotificationSuspensionBehaviorDeliverImmediately,
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun unregisterLivenessObserver() {
        CFNotificationCenterRemoveEveryObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            livenessObserverToken,
        )
    }

    /**
     * The extension finished a `process()` run: re-read the ledger counts (local, no network) so upload
     * status moves live while foreground. The gallery total and the foreign download line are refreshed
     * by their own triggers — this ding is upload-completeness only.
     */
    fun onUploadLivenessNotified() {
        scope.launch { ledgerCounts.refresh() }
    }

    /**
     * Re-read the own-device gallery total (enumeration, downloads suppressed) and the ledger counts
     * (completed + in-flight), plus the foreign download line. Full foreground refresh.
     */
    private suspend fun refreshStatusSources() {
        gallery.refresh()
        ledgerCounts.refresh()
        downloadStatusSource.refresh() // the "downloaded X of Y" line (capability `photo-download`)
    }

    /**
     * The system relaunched the app to finish background `URLSession` events. There is **no** app-owned
     * background session any more (the device manifest is PUT synchronously by the extension; bytes are
     * the OS upload-job system's), so this just invokes the OS [completionHandler] immediately. Kept so
     * the Swift app delegate's `handleEventsForBackgroundURLSession` seam stays a harmless pass-through.
     */
    /**
     * The `BGProcessingTask` import-tail backstop (capability `photo-download`, 5.4): drains any
     * staged-but-not-yet-imported foreign assets when no further download event would wake the app
     * (e.g. the last transfer overran its URLSession wake budget). OS-scheduled (idle/charging) via the
     * Swift host's `BGTaskScheduler` registration; [onComplete] maps to `task.setTaskCompleted`.
     * Discovery stays foreground-only — this imports already-downloaded work, it does not re-read the union.
     */
    fun runDownloadBackstop(onComplete: () -> Unit) {
        scope.launch {
            runCatching { downloadController.importReady() }
                .onFailure { log.w(it) { "download backstop import failed" } }
            scheduleDownloadBackstop() // re-arm for the next idle window
            onComplete()
        }
    }

    /** Queue a `BGProcessingTask` request so the OS runs [runDownloadBackstop] at a future idle moment. */
    @OptIn(ExperimentalForeignApi::class)
    fun scheduleDownloadBackstop() {
        val request = BGProcessingTaskRequest(DOWNLOAD_BACKSTOP_TASK_ID)
        request.requiresNetworkConnectivity = false // imports operate on already-staged bytes
        request.requiresExternalPower = false
        runCatching { BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null) }
            .onFailure { log.w(it) { "could not schedule download backstop" } }
    }

    fun handleBackgroundUrlSession(identifier: String, completionHandler: () -> Unit) {
        // Route by session identifier: the app-driven UPLOAD session (18–26.0) vs the download session.
        if (identifier == UrlSessionUploadController.SESSION_IDENTIFIER) {
            urlSessionUpload.onBackgroundSessionEvents(completionHandler)
            return
        }
        // Downloads: the OS relaunched us to deliver background download completions. Adopt the session
        // so its delegate fires (staging + import run), and invoke the OS handler once events drain.
        downloadJobs.adoptBackgroundEvents(completionHandler)
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
            is ConfigDecodeResult.Success -> scope.launch { provisionEvent(decoded.payload.eventId, name = null) }
            is ConfigDecodeResult.Failure -> host.onOpenUrl(url) // flashes the invalid-link error
        }
    }

    /**
     * The OS delivered an APNs device token (capability `push-registration`), forwarded raw-hex from the
     * Swift AppDelegate's `didRegisterForRemoteNotificationsWithDeviceToken`. Feed it to the token
     * source; the registration collector PUTs `devices/<id>/config`. Idempotent across launches and
     * rotations. Touch [host] so the collector is running to observe it. No decision in Swift.
     */
    fun onPushToken(hex: String) {
        host
        pushTokenSource.deliver(hex)
    }

    /**
     * A silent (`content-available`) remote notification arrived (capability `push-registration`),
     * forwarded from the Swift AppDelegate with the push payload's `eventId`. Route it to the receiver,
     * which — if [eventId] is the active event — reconciles downloads (union read + enqueue). We hold the
     * OS [completion] handler until the receiver's synchronous work finishes, so iOS keeps the app alive
     * through the enqueue (the background transfers then continue on their own). Touch [host] so the
     * download stack is assembled on a background launch. Non-throwing: a failure still calls [completion].
     */
    fun onSilentPush(eventId: String, completion: () -> Unit) {
        host
        scope.launch {
            runCatching { pushReceiver.onSilentPush(eventId) }
                .onFailure { log.w(it) { "silent push handling failed for $eventId" } }
            completion()
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
    private suspend fun provisionEvent(eventId: String, name: String?) {
        // Persist immediately (join never blocks on the cosmetic name); the container's ConfigSource
        // is this instance. Create passes the name straight through; scan passes null.
        config.save(EventConfig(eventId, name))
        refreshStatusSources() // (re)joined event → re-enumerate own total + re-LIST completeness
        if (permission.permission.value == PermissionStatus.GRANTED) enableBackgroundUpload()
        // Auto-download the other contributors' photos for this event (capability `photo-download`).
        scope.launch { downloadController.reconcile(eventId) }
        // Scan path: fill the title by id, best-effort, off the join (a failure leaves name null).
        if (name == null) scope.launch { fetchAndStoreName(eventId) }
    }

    /**
     * Best-effort fetch of the event name by id (`GET /event/:id`) and store it into the persisted
     * config — the scan-path name source and the foreground refresh. Non-throwing: a null (offline /
     * 404 / parse) leaves the current name unchanged.
     */
    private suspend fun fetchAndStoreName(eventId: String) {
        val fetched = metadataSource.name(eventId) ?: return
        val current = config.config.value
        if (current?.eventId == eventId && current.name != fetched) {
            config.save(EventConfig(eventId, fetched))
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
    // Disable the extension AND recover the jobs the disable wipes (capability `ios-background-upload`).
    // A disable (`setUploadJobExtensionEnabled(false)`) deletes the OS upload-job configuration, wiping
    // every in-flight job. Two clears make that recoverable:
    //   • clearRequested() — drop the now-orphaned REQUESTED rows (the engine never re-issues REQUESTED
    //     and no API surfaces the vanished job, so without this they stay REQUESTED forever);
    //   • reset the discovery cursor — clearRequested only makes the keys ABSENT; a settled cursor would
    //     scan only incrementally and never re-surface them, so force a FULL re-enumeration next cycle
    //     so they are re-discovered and re-created (COMPLETED rows stay, so stored files don't re-upload).
    // The SINGLE disable path for both the re-register toggle and leave, so they cannot diverge. Neither
    // is a per-key record write, so no `LedgerWriter` is built here. clearRequested is **awaited off-main
    // with a bounded retry** (the tested `clearRequestedOffMain`) and completes BEFORE any re-enable —
    // not the old fire-and-forget `scope.launch { clearRequested() }` on the main scope, which raced the
    // immediate re-enable and could delete the re-enabled extension's fresh REQUESTED rows (§7.1).
    private suspend fun disableExtension() {
        // App-driven tier: no OS extension to toggle — cancel in-flight transfers + heartbeat and wipe
        // the local ledger/cursor (leave). The app is the single writer here, so this owns the reset.
        if (useAppDrivenUpload) { urlSessionUpload.leave(); return }
        setUploadExtensionEnabled(false)
        NSUserDefaults(suiteName = LEDGER_APP_GROUP).removeObjectForKey(DISCOVERY_TOKEN_KEY)
        clearRequestedOffMain({ ledgerBackend.clearRequested() }, log = log)
    }

    private suspend fun enableBackgroundUpload() {
        disableExtension() // awaited: the off-main REQUESTED clear completes BEFORE the re-enable below
        setUploadExtensionEnabled(true)
        log.i { "background-upload extension re-registered (disable→enable, cleared REQUESTED)" }
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
                if (status != PermissionStatus.GRANTED) return@collect
                // Per-version tier selection: PhotoKit extension on ≥26.1; the in-app URLSession pump on
                // 18–26.0 (or the dev force flag). The app-driven start sweeps orphaned staging + pumps a cycle.
                if (useAppDrivenUpload) urlSessionUpload.start() else enableBackgroundUpload()
            }
        }
    }

    // Dev/test force flag (like SNAPSYNC_DEEPLINK): forces the app-driven URLSession upload tier even on
    // iOS ≥26.1 so it can be exercised on the simulator (which cannot run the PhotoKit extension). Inert
    // in production — a launch env var is only injectable via a developer launch.
    private val forceUrlSessionUpload: Boolean =
        NSProcessInfo.processInfo.environment["SNAPSYNC_FORCE_URLSESSION_UPLOAD"] != null

    /** True when uploads run in-process over a background URLSession (iOS 18–26.0, or the force flag). */
    private val useAppDrivenUpload: Boolean
        get() = !backgroundUploadSupported() || forceUrlSessionUpload

    // The app-driven (iOS 18–26.0) upload tier's composition root. Built lazily and used only when
    // [useAppDrivenUpload]; on iOS ≥26.1 without the force flag it is never touched (the extension runs).
    private val urlSessionUpload: UrlSessionUploadController by lazy {
        // Force-flagged (simulator) runs use a foreground session — the sim can't run a background one.
        UrlSessionUploadController(
            scope, ledgerBackend, config, deviceId, backendHost, log,
            httpClient = darwinHttpClient(),
            suppressedAssetIds = { downloadStore.suppressedLocalIds() },
            useBackgroundSession = !forceUrlSessionUpload,
            // In-process liveness: after each pump cycle, re-read the ledger counts so status moves live.
            onCycleComplete = { ledgerCounts.refresh() },
        )
    }

    /** The upload heartbeat BGProcessingTask handler (app-driven tier). Registered in the Swift shell. */
    fun runUploadHeartbeat(onComplete: () -> Unit) {
        if (useAppDrivenUpload) urlSessionUpload.onBackgroundTask(onComplete) else onComplete()
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

/**
 * The `CFNotificationCenter` Darwin callback (must be a top-level, non-capturing function to be a C
 * function pointer). It ignores its arguments and pokes the [SnapSyncRoot] singleton, which re-reads the
 * ledger counts on the app scope.
 */
@OptIn(ExperimentalForeignApi::class)
private fun uploadLivenessCallback(
    center: CFNotificationCenterRef?,
    observer: COpaquePointer?,
    name: CFNotificationName?,
    obj: COpaquePointer?,
    userInfo: CFDictionaryRef?,
) {
    SnapSyncRoot.onUploadLivenessNotified()
}
