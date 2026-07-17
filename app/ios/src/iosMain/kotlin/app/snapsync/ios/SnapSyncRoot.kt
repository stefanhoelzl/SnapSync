package app.snapsync.ios

import app.snapsync.model.EventConfig
import app.snapsync.config.KeychainConfigStore
import app.snapsync.feature.creation.CreateEvent
import app.snapsync.feature.creation.EventCreator
import app.snapsync.eventcreation.HttpEventCreation
import app.snapsync.feature.creation.MutableCreationStatusSource
import app.snapsync.feature.trust.DeviceAttestation
import app.snapsync.attest.HttpAttestClient
import app.snapsync.attest.IosAttestKey
import app.snapsync.attest.KeychainAttestStore
import app.snapsync.ports.EventDetails
import app.snapsync.join.HttpEnrollment
import app.snapsync.join.HttpEventDirectory
import app.snapsync.feature.membership.JoinEvent
import app.snapsync.feature.membership.JoinOutcome
import app.snapsync.feature.membership.ManifestDeviceEnroller
import app.snapsync.presentation.JoinLoad
import app.snapsync.model.Contribution
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
import app.snapsync.model.PermissionStatus
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.presentation.MutableAttestedSource
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.forgeStatusHost
import app.snapsync.presentation.isForgeState
import app.snapsync.feature.download.DownloadPushReceiver
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.ports.PushReceiver
import app.snapsync.push.PushRegistration
import app.snapsync.ports.PushTokenSource
import app.snapsync.membership.HttpLeaveNotifier
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.membership.darwinHttpClient
import app.snapsync.feature.download.DownloadController
import app.snapsync.download.HttpEventUnionSource
import app.snapsync.download.IosDownloadTransport
import app.snapsync.feature.download.QueuedPhotoDownloadJobs
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.model.DENYLISTED_ALBUM_TITLES
import app.snapsync.album.IosAlbumManager
import app.snapsync.album.IosAlbumMapStore
import app.snapsync.download.IosPhotoLibraryImporter
import app.snapsync.model.denormalizeAssetId
import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.downloadstore.SqlDelightDownloadStore
import app.snapsync.downloadstore.iosDownloadStore
import platform.Foundation.NSFileManager
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.ports.LedgerStore
import app.snapsync.engine.iosLedgerStore
import app.snapsync.feature.status.LedgerBackedSyncStatusSource
import app.snapsync.feature.status.LedgerCounts
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.feature.status.ReadingLedgerCountsSource
import app.snapsync.model.UPLOAD_LIVENESS_DARWIN_NAME
import app.snapsync.upload.FanOutPushReceiver
import app.snapsync.feature.upload.UploadArm
import app.snapsync.feature.upload.UploadProducer
import app.snapsync.logging.FileLogWriter
import app.snapsync.logging.PublicNSLogWriter
import app.snapsync.keychain.KeychainDeviceIdentity
import app.snapsync.keychain.ProtectedDataGate
import app.snapsync.model.invocation
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
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
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSPredicate
import platform.Foundation.NSProcessInfo
import platform.Photos.PHAsset
import platform.Photos.PHFetchOptions
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
 * (one adapter implements both).
 *
 * **Upload lifecycle lives elsewhere.** This root selects exactly one [UploadProducer] for the process
 * (the PhotoKit extension registration on iOS ≥26.1, the in-app URLSession pump on 18–26.0) and forwards
 * membership transitions to the tested, tier-neutral [UploadArm] in `:capability:upload`. The *decision* —
 * which verb fires on provision / grant / leave — is not made here, because this module is wiring-only and
 * untested by the project's hard rule, and parking that decision here is precisely how the app-driven tier
 * shipped a provision path that destroyed its ledger and started nothing (capability `upload-lifecycle`).
 */
object SnapSyncRoot {

    init {
        // Route kermit through a public NSLog writer AND a file writer. NSLog is redacted as
        // `<private>` on current iOS (dynamic format strings are private), so the file writer
        // (Documents/debug.log, pulled via `pymobiledevice3 apps pull`) is the reliable channel.
        // Both are consolidated in `:domain:logging`; each line carries the ambient `[entryPoint]`.
        Logger.setLogWriters(PublicNSLogWriter(), FileLogWriter())
        // Boot banner (capability `diagnostic-logging`, D5) — names the process + build version so a
        // reader who concatenates the app/extension files can tell runs apart. `log` isn't assigned
        // yet in this init block, so use a fresh tagged logger.
        Logger.withTag("SnapSyncRoot").i { "=== app process start build=${buildVersion()} ===" }
    }

    private val log = Logger.withTag("SnapSyncRoot")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** BGTaskScheduler identifier for the download import-tail backstop — MUST match the Swift host's
     * `register(forTaskWithIdentifier:)` and the Info.plist `BGTaskSchedulerPermittedIdentifiers`. */
    const val DOWNLOAD_BACKSTOP_TASK_ID: String = "app.snapsync.download.backstop"

    // The event config seam/store (one Keychain adapter is both), hoisted so a (re)provision can read
    // the current event id and the leave use-case can clear it.
    private val config: KeychainConfigStore by lazy { KeychainConfigStore() }

    /**
     * Protected-data availability (capability `ios-app-shell`): the Keychain and the app/App-Group
     * containers are unreadable before the first unlock since boot, and a background wake can land in
     * exactly that window. Rather than attempting a read, failing, and *interpreting* the failure — which
     * is how a locked device came to mint a new device id (aborting the process) and how the extension
     * came to read "no config" as a leave — the app asks iOS, and **defers** the work to the unlock.
     *
     * The gate's decision logic is tested in `:domain:keychain`; [IosProtectedData] is the thin
     * `UIApplication` adapter, which lives here because that API is unavailable to app extensions.
     */
    private val protectedData: ProtectedDataGate by lazy {
        ProtectedDataGate(IosProtectedData(), log).also { gate ->
            // A background launch before the first unlock seeds an unreadable — therefore empty — config
            // StateFlow. Re-read it the moment protected data arrives, or the screen would sit at the
            // setup gate (and the upload arm stay idle) despite a perfectly good persisted membership.
            gate.runWhenAvailable("reloadConfigOnUnlock") { config.reload() }
        }
    }

    // Event album (capability `event-album`): the shared leave-surviving `eventId → albumLocalId` map and
    // the coordinator. The APP is the SOLE creator (on the permission grant); both processes only add.
    private val albumMapStore: IosAlbumMapStore by lazy { IosAlbumMapStore() }
    // Hoisted: the selection policy also reads the manager (denylisted-album membership), not just the
    // coordinator (capability `photo-selection-policy`).
    private val albumManager: IosAlbumManager by lazy { IosAlbumManager() }
    private val albumCoordinator: AlbumCoordinator by lazy {
        AlbumCoordinator(albumManager, albumMapStore)
    }

    /**
     * The normalized `assetId`s sitting in an album a messaging/social app made (capability
     * `photo-selection-policy`). Supplied to BOTH the upload cycle (via the app-driven tier's controller)
     * and the own-device status total — they enumerate independently, so a rule applied to one and not the
     * other would peg the joined screen below 100% forever.
     */
    private suspend fun albumExcludedAssetIds(cutoff: String): Set<String> =
        runCatching { albumManager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff) }
            .onFailure { log.w(it) { "denylisted-album lookup failed — admitting on doubt this cycle" } }
            .getOrDefault(emptySet()) // admit-on-doubt: a failed lookup must never DROP a real photo

    // The event album's `localIdentifier` for the CURRENT membership, or null when it opted out or the
    // album has not been created yet — the atomic album-add lookup the download importer borrows.
    private fun currentAlbumId(): String? {
        val cfg = config.config.value ?: return null
        return if (cfg.saveToAlbum) albumMapStore.get(cfg.eventId) else null
    }

    // Create the event album now if the current membership opted in and it does not exist yet — the app
    // is the sole creator. Idempotent: reuses an existing album, recreates a deleted one. Runs on the
    // permission grant and on provision (when already granted), so the album exists before the first sync.
    private suspend fun ensureAlbumIfOptedIn() {
        val cfg = config.config.value ?: return
        if (cfg.saveToAlbum && cfg.name.isNotEmpty()) albumCoordinator.ensureAlbum(cfg.eventId, cfg.name)
    }

    // The photo-library permission adapter, hoisted so the grant collector and a (re)provision share one
    // instance (both enable the extension; a provision must re-enable a producer a prior leave disabled).
    private val permission: PhotoLibraryPermission by lazy { PhotoLibraryPermission() }

    // The stable per-install device id (shared Keychain — the SAME item the extension reads): the
    // `/files/devices/<deviceId>/` partition the app's status lists. (Finishes wiring `device-identity` into
    // both roots.)
    private val deviceId: String by lazy { KeychainDeviceIdentity().deviceId() }

    /**
     * Device attestation (capability `device-attestation`) — the bearer token EVERY backend call carries.
     *
     * Lives in the app root because only the app CAN attest: `DCAppAttestService.isSupported` is `false`
     * inside the upload extension and `true` here (measured on device). The extension is a pure reader of
     * the token this writes into the shared Keychain.
     *
     * Its own HTTP client is deliberately UNauthenticated: the three `/attest/…` routes are the ones that
     * issue the token, so authenticating them would be a cycle — and this lazy would deadlock on itself.
     */
    private val attestation: DeviceAttestation by lazy {
        DeviceAttestation(
            key = IosAttestKey(),
            client = HttpAttestClient(darwinHttpClient(), backendHost),
            store = KeychainAttestStore(),
            deviceId = KeychainDeviceIdentity()::deviceId,
            now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
        )
    }

    /**
     * The ONE authenticated HTTP client every backend call goes through — create, event fetch, join,
     * manifest, union, device config, leave, notify. Built once and shared, so no call site can be
     * forgotten and a future one inherits the token for free. The token is read per request, so a renewal
     * in the background is picked up without rebuilding anything.
     */
    private val http: HttpClient by lazy {
        darwinHttpClient(
            token = { attestation.token() },
            // Rejected (not merely expired) → drop it and go get a new one right now. We are demonstrably
            // online (the backend just answered), so this is the best possible moment to recover.
            onRejected = {
                attestation.onRejected()
                refreshAttestation()
            },
        )
    }

    /**
     * Refresh the token if it is stale. Called at EVERY point this process is already awake — launch,
     * foreground, a silent-push wake, and each `BGTask` handler — rather than from a dedicated background
     * task: iOS budgets task identifiers per app, so a third one would compete with the two we have and
     * would still fire only when the system felt like it. Checking at every wake gets strictly more
     * chances to renew than any schedule could.
     *
     * Best-effort and non-throwing: a background wake must not die because attestation failed.
     */
    private fun refreshAttestation() {
        scope.launch {
            val ok = runCatching { attestation.ensureFresh() }.getOrDefault(false)
            // Surface it ONLY when we both lack a usable token and could not get one. A stale token that
            // renews is a non-event; raising it would be noise. And because opening the app IS a wake, this
            // normally clears before it can be seen — what survives is a device that is offline or being
            // refused, which is a real problem that no amount of waiting fixes.
            attested.set(ok || !attestation.isStale(attestation.token()))
        }
    }

    /** Drives `SyncHealth.Unattested` (capability `device-attestation`). See [refreshAttestation]. */
    private val attested = MutableAttestedSource()

    // The own-device upload TOTAL N (capability `sync-status`): gallery enumeration minus downloaded
    // foreign photos (suppressed from upload — they live in the library but must not peg progress below
    // 100%, capability `photo-download`). Enumeration-only — no storage LIST (completeness now comes
    // from the ledger, below). Refreshes on foreground entry.
    // The total is cutoff-scoped so the joined screen reaches "in sync" (capability `photo-selection-policy`):
    // pre-cutoff assets never upload, so they must not inflate `N`. The cutoff is supplied per refresh,
    // from the joined membership — an unjoined device has no scope and is never refreshed.
    // The origin exclusions scope the total too (capability `photo-selection-policy`): a screenshot is never
    // uploaded, so counting it would peg the screen below 100% exactly as a pre-cutoff asset would. The
    // resource-fact rules are applied inside the source; album membership comes through this lookup — the
    // SAME one the upload cycle gets, because the two enumerate independently.
    private val gallery: OwnDeviceGalleryStatusSource by lazy {
        OwnDeviceGalleryStatusSource(
            PhotoLibraryResourceEnumerator(),
            suppressedLocalIds = { downloadStore.suppressedLocalIds() },
            albumExcludedAssetIds = { cutoff -> albumExcludedAssetIds(cutoff) },
        )
    }

    // The app-side handle on the extension's shared App-Group ledger, used for two narrow things only:
    // a READ-ONLY aggregates read (`completed`/`pending`, below) and a reset-family `clearRequested()`
    // on extension disable (recover jobs the disable wiped, capability `ios-background-upload`). No
    // per-key record writes and no `LedgerWriter` — the extension stays the sole record writer. WAL
    // permits the concurrent cross-process read.
    private val ledgerStore: LedgerStore by lazy { iosLedgerStore() }

    // Own-device completeness AND in-flight, both from one consistent ledger `aggregates()` read
    // (capability `sync-status`). Read-only; on any failure the last good counts are retained (never
    // regressed to 0). Refreshed on foreground entry AND on the extension's cross-process liveness
    // notification (below).
    private val ledgerCounts: ReadingLedgerCountsSource by lazy {
        ReadingLedgerCountsSource {
            ledgerStore.aggregates().let { LedgerCounts(completed = it.completed, pending = it.pending) }
        }
    }

    // --- Photo download / import (capability `photo-download`) ---
    // The app-written download store (idempotency + per-resource staging + the createdLocalId the
    // extension reads as its suppression set). Concrete type so the importer can write createdLocalId
    // synchronously from inside a PhotoKit change block.
    private val downloadStore: SqlDelightDownloadStore by lazy { iosDownloadStore() }

    // Background-URLSession byte transfers (Wi-Fi + cellular) → durable App-Group staging. The queue,
    // bounded window, and cancellation lifecycle live in the tested QueuedPhotoDownloadJobs; the
    // NSURLSession edge is IosDownloadTransport, rebuilt if the system ever invalidates the session.
    private val downloadJobs: QueuedPhotoDownloadJobs by lazy {
        val container = NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)?.path
            ?: error("App Group container '$LEDGER_APP_GROUP' unavailable")
        QueuedPhotoDownloadJobs(
            scope,
            "$container/download-staging",
            newTransport = { host -> IosDownloadTransport(host) },
        )
    }

    // The orchestrator: union → foreign selection → download → full-fidelity import → suppression.
    private val downloadController: DownloadController by lazy {
        val uploadHost = NSBundle.mainBundle.objectForInfoDictionaryKey("BackgroundUploadURLBase") as? String ?: ""
        val controller = DownloadController(
            union = HttpEventUnionSource(http, uploadHost),
            store = downloadStore,
            jobs = downloadJobs,
            importer = IosPhotoLibraryImporter(
                recordCreatedLocalId = { ref, id -> downloadStore.recordCreatedLocalId(ref, id) },
                // Event album (capability `event-album`): add each imported foreign asset to the event
                // album atomically, sourced from the shared map (null = opt-out or not-yet-created).
                albumId = { currentAlbumId() },
            ),
            myDeviceId = deviceId,
            // The download arm runs only when the joined membership's direction includes download
            // (capability `join-event`) — an upload-only membership reconciles nothing. No event joined
            // (config null) → nothing to reconcile anyway; default true is harmless there.
            // Three-valued, no fallback: no membership → `null` → no arm. The `?: true` this replaces made
            // "no membership" mean "download freely" (capability `photo-download`).
            downloadEnabled = { config.config.value?.direction?.includesDownload },
        )
        // Deliver each staged resource back to the controller off the URLSession delegate thread.
        downloadJobs.onStaged = { ref, key, path -> scope.launch { controller.onResourceStaged(ref, key, path) } }
        controller
    }

    // Download progress for the joined-layer "downloaded X of Y" line (capability `photo-download`),
    // read from the store. Refreshed on foreground entry alongside the upload status.
    private val downloadStatusSource: StoreDownloadStatusSource by lazy { StoreDownloadStatusSource(downloadStore) }

    // The seam that tells the backend this device is leaving (DELETE /events/<id>/devices/<id>,
    // capability `event-leave-endpoint`) — the backend renames the manifest to its departed
    // `.left.json` sibling and reaps/GCs the event when the last member leaves. Best-effort (a failed
    // call never blocks leaving). Used by BOTH the explicit Leave and a switch (see [provisionEvent]).
    private val leaveNotifier: HttpLeaveNotifier by lazy { HttpLeaveNotifier(http, backendHost) }

    // The leave use-case: stops the producer, clears the Keychain config (which flips the screen off the
    // joined layer), then fires the backend notify fire-and-forget on the app-lifetime `scope` so a slow
    // DELETE never freezes the screen. It constructs no ledger type and **destroys no dedup state**: the
    // ledger, discovery cursor, and accumulator are device-global and stay valid across events, so a later
    // join re-uploads nothing already in this device's byte partition. The reconciler clears the
    // `joinedEventId` marker on the next cycle (`event-rejoin-reconciliation`). The eventId is snapshotted
    // before the clear and passed into the notify.
    private val leaveEvent: LeaveEvent by lazy {
        LeaveEvent(
            config = config,
            configSource = config,
            stopUploads = { uploadArm.onLeave() },
            notifyLeave = { eventId -> leaveNotifier.leave(eventId, deviceId) },
            scope = scope,
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

    // The ONE GET /events/:id client (capability `join-event`): the join gate's details fetch and the
    // best-effort scan-path/foreground name refresh both read through it.
    private val detailsSource: HttpEventDirectory by lazy {
        HttpEventDirectory(http, backendHost)
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
        PushRegistration(KtorPushHttpClient(http), backendHost, deviceId)
    }

    // The silent-push receivers, fanned out to BOTH arms. A push means "the event changed", which is news
    // to each of them: foreign photos to pull (capability `photo-download`), and — since the event is
    // demonstrably live — a good moment to contribute our own (capability `ios-url-session-upload`).
    //
    // Each arm keeps its OWN active-event guard, in its own tested capability; this root only composes them.
    // Both guards answer "is this push for my current event", so a push for any other event (e.g. a
    // locally-left event whose backend membership persists and keeps pushing us) is a no-op in both.
    //
    // The upload arm is added only on the app-driven tier, where a pump exists to drive. On iOS ≥26.1 the OS
    // owns upload scheduling and we have no lever — the extension is invoked on its own cadence.
    private val pushReceiver: PushReceiver by lazy {
        val download = DownloadPushReceiver(
            activeEventId = { config.config.value?.eventId },
            controller = downloadController,
        )
        if (!useAppDrivenUpload) return@lazy download
        FanOutPushReceiver(listOf(download, urlSessionUpload.pushReceiver))
    }

    private val eventCreator: EventCreator by lazy {
        CreateEvent(
            client = HttpEventCreation(http, backendHost),
            status = creationStatus,
            // Route the minted event into the SAME join gate a scan uses (capability `photo-selection-policy`):
            // the creator loads the event, picks a capture-date cutoff, and confirms like any joiner.
            onMinted = { eventId -> host.onEventCreated(eventId) },
            scope = scope,
        )
    }

    // The join use-case (capability `join-event`): fetch details (GET /event/:id, 200/404/failure),
    // enroll by writing a register-only EMPTY device manifest (PUT /event/:id/device/:deviceId), then
    // provision through the same path as create/scan. The switch (leave-then-join) is composed in the
    // container, so this stays free of the leave use-case.
    private val joinEvent: JoinEvent by lazy {
        JoinEvent(
            configSource = config,
            deviceId = { deviceId },
            details = detailsSource,
            enroller = ManifestDeviceEnroller(HttpEnrollment(http, backendHost)),
            provision = ::provisionEvent,
        )
    }

    val host: StatusContainerHost by lazy {
        // The own-device source: ledger completeness + in-flight (one aggregates() read) × permission ×
        // the live own-device gallery total, minted into snapshots. Ledger-sourced, no storage LIST for
        // upload status (spec: sync-status); safe under no-deletion-during-an-active-event.
        val syncSource = LedgerBackedSyncStatusSource(ledgerCounts, permission, gallery, scope)
        startUploadsOnGrant()
        ensureAlbumOnGrant()
        // Start registering the APNs token: the collector reacts to each token the AppDelegate delivers
        // (StateFlow-retained, so a token delivered before this launches is still registered).
        //
        // ATTEST FIRST. The registration `PUT /devices/<id>` is gated, and on a fresh install the APNs
        // token can arrive before this device has any attestation token — which is exactly what happened
        // on the SE2: the PUT took a 401. Awaiting `ensureFresh` first removes that race.
        //
        // `tokenChanged` is the backstop that retries a refused registration WITHIN this process
        // lifetime. (Not because the token arrives only once — Apple documents an up-to-date token on
        // EVERY successful registration, and AppDelegate registers every launch — but a PUT refused
        // now would otherwise wait for the next launch to be retried: no silent pushes, no download
        // wakes, none of the wake-driven renewals until then.) Any new credential re-runs it.
        scope.launch {
            runCatching { attestation.ensureFresh() }
            pushRegistration.run(pushTokenSource, attestation.tokenChanged)
        }
        // `config` is passed as both ports (one Keychain adapter implements both), as `permission` is.
        // No EventStatus source: status is read from the listing; the extension owns reconciliation.
        StatusContainerHost(
            syncSource, permission, permission, config, config, scope,
            creationStatusSource = creationStatus, creator = eventCreator,
            // Leave: cancel in-flight downloads and drop non-terminal rows (imported photos stay;
            // suppression rows are permanent), then run the leave use-case (disable producer → notify the
            // backend it is leaving → clear config/producer). Imported foreign photos are never touched.
            leave = { downloadController.onLeaveOrSwitch(); leaveEvent.leave() },
            // Fire-and-forget share of the invite link (the host owns the URL). Wiring-only:
            // present the system share sheet over the current top view controller.
            share = { url -> presentShareSheet(url) },
            // The join gate (capability `join-event`): a scanned QR opens the confirmation; details are
            // fetched (GET), confirming enrolls (empty-manifest PUT) then provisions. `commitJoin` is
            // true unless enrollment failed (the same-event no-op is a success).
            loadJoinDetails = { eventId -> joinEvent.loadDetails(eventId).toJoinLoad() },
            commitJoin = { eventId, name, startsAt, cutoff, direction, saveToAlbum ->
                joinEvent.join(eventId, name, startsAt, cutoff, direction, saveToAlbum) !=
                    JoinOutcome.EnrollFailed
            },
            log = { message -> log.i { message } },
            downloadSource = downloadStatusSource,
            attestedSource = attested,
        )
    }

    // The dev/test `SNAPSYNC_FORGE_STATE` launch-env variable (capability `ios-app-shell`), read once
    // per process. When it names a recognized forge state the app renders a forged screen for a
    // marketing screenshot and MUST NOT boot the live stack: the unsigned simulator the screenshots run
    // in has no App-Group ledger container, no App Attest, no PhotoKit grant, and no backend — touching
    // any of them crashes the process. Inert in production (a launch env var is only injectable via a
    // developer launch, exactly as with `SNAPSYNC_EVENT_LINK`).
    private val forgeState: String? = NSProcessInfo.processInfo.environment["SNAPSYNC_FORGE_STATE"] as? String
    private val isForging: Boolean = forgeState?.let { isForgeState(it) } == true

    /**
     * The host [MainViewController] renders. Resolved **once per process** (`by lazy`): the forged host
     * when [isForging], else the live [host]. When forging the live [host] is never touched, so the real
     * stack is never assembled.
     */
    val renderHost: StatusContainerHost by lazy {
        if (isForging) {
            log.i { "rendering SNAPSYNC_FORGE_STATE=$forgeState" }
            forgeStatusHost(forgeState!!, scope)!!
        } else {
            host
        }
    }

    // Adapt the join capability's [EventDetails] to the presentation-local [JoinLoad] the gate consumes.
    private fun EventDetails.toJoinLoad(): JoinLoad = when (this) {
        is EventDetails.Found -> JoinLoad.Found(name, startsAt)
        EventDetails.NotFound -> JoinLoad.NotFound
        EventDetails.Failed -> JoinLoad.Failed
    }

    /**
     * The SwiftUI scene's foreground transition (forwarded from the `@main` scene's scenePhase):
     * re-read the per-device file listing (completeness) and the ledger's in-flight count so status
     * reflects any uploads that progressed while backgrounded (capability `sync-status` liveness).
     * Touching [host] ensures the stack is assembled before the first transition arrives.
     */
    fun onForeground() = log.invocation(
        "onForeground",
        params = "useAppDrivenUpload=$useAppDrivenUpload force=$forceUrlSessionUpload osSupported=${backgroundUploadSupported()}",
    ) {
        // In forge mode the process exists only to render a screenshot; booting the live stack here
        // (App-Group ledger, App Attest, PhotoKit, network) would crash the unsigned simulator app.
        if (isForging) {
            log.i { "forge mode: skipping live foreground work" }
            return@invocation
        }
        host
        // Listen for the extension's cross-process liveness ding while foreground, so upload status moves
        // live as the extension records completions/new jobs (spec: sync-status). Foreground-only: a
        // suspended app cannot act on the post, and this foreground entry already re-reads below.
        registerLivenessObserver()
        // App-driven upload tier (iOS 18–26.0): foreground entry pumps an upload cycle (completions then
        // keep it draining while the app is open). No-op on ≥26.1 (the OS drives the extension).
        if (useAppDrivenUpload) urlSessionUpload.onForeground()
        // These launches escape this entry point's synchronous span, so each labels itself via its own
        // wrapped seam (reconcile/refresh); onForeground's own context covers only the dispatch here.
        scope.launch { refreshStatusSources() }
        // Foreground-only discovery (capability `photo-download`): pick up foreign photos others added
        // since the last read, and import anything already staged. No background poll.
        scope.launch { config.config.value?.eventId?.let { downloadController.reconcile(it) } }
        // Keep the event title current (fills a name a scan couldn't fetch while offline).
        scope.launch { config.config.value?.eventId?.let { fetchAndStoreName(it) } }
        // Wake point (capability `device-attestation`): renew the token if it is stale. This one also
        // covers LAUNCH, which is the first foreground.
        refreshAttestation()
    }

    /**
     * On backgrounding, queue the download import-tail backstop so any staged-but-unimported foreign
     * assets get imported at the next idle/charging window even if no further download wakes the app
     * (capability `photo-download`, 5.4). Status liveness itself stays event-driven (foreground entry).
     */
    fun onBackground() = log.invocation("onBackground") {
        if (isForging) return@invocation
        unregisterLivenessObserver()
        scheduleDownloadBackstop()
        log.i { "=== app entering background ===" }
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
        // No membership → nothing to count; `N` stays 0 and the screen is at the setup gate anyway
        // (capability `photo-selection-policy`). A membership that contributes nothing (download-only)
        // counts 0 too — but that is the source's decision, from the Contribution, not this shell's: the
        // roots pass facts, never branches.
        config.config.value?.let { cfg ->
            gallery.refresh(Contribution.of(cfg.direction.includesUpload, cfg.minPhotoDate))
        }
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
            // Wrap INSIDE the launch so `[runDownloadBackstop]` spans the async import. The
            // protected-data state rides the entry-point line (capability `ios-app-shell`): a background
            // wake on a locked device is otherwise invisible, and it is the only place this class of bug
            // shows up — no test can reach it.
            log.invocation("runDownloadBackstop", params = "protectedData=${protectedData.isAvailable()}") {
                // The import reads the download store and PhotoKit, and its album placement reads the
                // album map. If protected data is unavailable (before the first unlock since boot), defer
                // the whole thing to the unlock rather than letting it fail — and, critically, without
                // touching the Keychain, which is what minted a device id and aborted the process.
                protectedData.runWhenAvailable("runDownloadBackstop") {
                    // Wake point (capability `device-attestation`). This BGTask is the one recurring wake
                    // the app gets that does NOT depend on an upload having succeeded — which matters,
                    // because an expired token is exactly what stops uploads succeeding.
                    refreshAttestation()
                    scope.launch {
                        runCatching { downloadController.importReady() }
                            .onFailure { log.w(it) { "download backstop import failed" } }
                    }
                }
            }
            // Re-arm for the next idle window and release the OS's task assertion immediately — even when
            // the work above was deferred, since holding the BGTask open until an unlock is not an option.
            scheduleDownloadBackstop()
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

    fun handleBackgroundUrlSession(identifier: String, completionHandler: () -> Unit) = log.invocation(
        "handleBackgroundUrlSession",
        params = "identifier=$identifier protectedData=${protectedData.isAvailable()}",
    ) {
        // Route by session identifier: the app-driven UPLOAD session (18–26.0) vs the download session.
        if (identifier == UrlSessionUploadController.SESSION_IDENTIFIER) {
            urlSessionUpload.onBackgroundSessionEvents(completionHandler)
            return@invocation
        }
        // Downloads: the OS relaunched us to deliver background download completions. Adopt the session
        // so its delegate fires (staging + import run), and invoke the OS handler once events drain.
        downloadJobs.adoptBackgroundEvents(completionHandler)
    }

    /**
     * An event link arrived (forwarded raw from the Swift entry point — the complete URL, fragment
     * included, since the fragment carries the whole payload). Routed straight to
     * the container's **join gate** (capability `join-event`): it decodes, and either opens the
     * confirmation (a first join → full-screen; a different event while joined → switch dialog),
     * auto-confirms when the link carries `autoJoin=true` (the dev/headless trigger), or flashes the
     * invalid-link error. The app no longer provisions directly on scan — the gate owns that.
     */
    fun onOpenUrl(url: String) = log.invocation("onOpenUrl", params = "url=$url") {
        // Forge mode: unreachable today only by accident — a screenshot run sets no `SNAPSYNC_EVENT_LINK`
        // and taps no link — but the rule is "while forging, the app assembles no live stack", not "the
        // three hooks we happened to think of". Setting both env vars would otherwise provision a real
        // event from a process rendering a forged frame, which is incoherent before it is a crash.
        if (isForging) {
            log.i { "forge mode: ignoring event link" }
            return@invocation
        }
        host.onOpenUrl(url)
    }

    /**
     * The OS delivered an APNs device token (capability `push-registration`), forwarded raw-hex from the
     * Swift AppDelegate's `didRegisterForRemoteNotificationsWithDeviceToken`. Feed it to the token
     * source; the registration collector PUTs `devices/<id>/config`. Idempotent across launches and
     * rotations. Touch [host] so the collector is running to observe it. No decision in Swift.
     */
    fun onPushToken(hex: String) = log.invocation("onPushToken", params = "hex=${hex.take(12)}…") {
        // Forge mode: `registerForRemoteNotifications()` is called unconditionally at launch, so this
        // arrives on a screenshot run too — and touching [host] below would assemble the live stack on an
        // unsigned simulator with no App-Group container, no App Attest, and no photo grant. Registering a
        // token for a process that exists only to render one frame buys nothing, so drop it.
        if (isForging) {
            log.i { "forge mode: ignoring push token" }
            return@invocation
        }
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
        // Forge mode: same reason as [onPushToken] — touching [host] would boot the live stack on an
        // unsigned simulator. [completion] is still invoked, and that is not optional: it is the OS's
        // handler, and an unanswered `content-available` push costs the app its future background wakes.
        // Returning without calling it would trade a crash for a slower, invisible penalty.
        if (isForging) {
            log.i { "forge mode: ignoring silent push for $eventId" }
            completion()
            return
        }
        host
        scope.launch {
            // Wrap INSIDE the launch so `[onSilentPush]` spans the async reconcile (and the download
            // HTTP + import lines it drives trace back to this push).
            log.invocation(
                "onSilentPush",
                params = "eventId=$eventId protectedData=${protectedData.isAvailable()}",
            ) {
                // A silent push is delivered to a locked device as readily as an unlocked one, and the
                // reconcile reads the config (Keychain) and the download store. Defer rather than fail.
                protectedData.runWhenAvailable("onSilentPush") {
                    // Wake point (capability `device-attestation`). Inside the protected-data gate: the
                    // Keychain holding the token is unreadable before the first unlock since boot.
                    refreshAttestation()
                    scope.launch {
                        runCatching { pushReceiver.onSilentPush(eventId) }
                            .onFailure { log.w(it) { "silent push handling failed for $eventId" } }
                    }
                }
            }
            // Always release the OS handler promptly — iOS gives a silent push a short budget and holding
            // it open until an unlock would simply get us killed.
            completion()
        }
    }

    /**
     * Provision an event id — the shared path for both a scanned or typed event link and a freshly created
     * event. Persists the config (the container's `ConfigSource` is this instance), re-reads the gallery
     * total and the storage-truth status sources, then **starts** the producer if access is granted (via
     * the tested, tier-neutral [UploadArm]). The app runs no join, fetch, or seed — the upload cycle
     * self-reconciles, gated by its `joinedEventId` marker (`event-rejoin-reconciliation`).
     *
     * Starting here is load-bearing: the grant collector fires only on a *transition* to GRANTED, so a
     * membership provisioned while access is already granted — the common case for every join after the
     * first — would otherwise never start uploading at all.
     *
     * Nothing is cancelled, toggled, or reset. This path used to run a PhotoKit-shaped "disable→enable"
     * ritual regardless of tier; on the app-driven tier its disable half resolved to a full *leave*
     * (cancelling transfers and the heartbeat, wiping the ledger and the discovery cursor) while its enable
     * half was a no-op below iOS 26.1 — so joining an event tore the upload arm down and started nothing,
     * then re-uploaded the whole post-cutoff library. The seam now has no destructive verb to reach.
     */
    // Persist the WHOLE [EventConfig] the join/create use-case built and run the join side effects.
    // Taking the object (not its fields) is deliberate: the composition root must never destructure and
    // rebuild it, or a newly-added field (the `minPhotoDate` cutoff was such a field) is silently dropped
    // before the Keychain save the extension reads. Named `cfg` to avoid shadowing the `config` store.
    private suspend fun provisionEvent(cfg: EventConfig) = log.invocation(
        "provisionEvent",
        params = "eventId=${cfg.eventId} named=${cfg.name.isNotEmpty()} cutoff=${cfg.minPhotoDate}",
    ) {
        // Switch: provisioning a DIFFERENT event while joined leaves the previous one on the backend
        // first (best-effort — a failure never prevents the switch; see `event-link`). Re-scanning
        // the same event is not a switch and fires no leave. The confirm dialog for this is a later change.
        config.config.value?.eventId?.let { previous ->
            if (previous != cfg.eventId) leaveNotifier.leave(previous, deviceId)
        }
        // Persist the full config as-is (join never blocks on the cosmetic name); the container's
        // ConfigSource is this instance. The per-device capture-date cutoff rides along untouched, so the
        // extension reads it (capability `photo-selection-policy`).
        config.save(cfg)
        refreshStatusSources() // (re)joined event → re-enumerate own total + re-LIST completeness
        // Drive the upload arm through the tested, tier-neutral lifecycle (capability `upload-lifecycle`):
        // with access granted it STARTS the producer (or stops it for a download-only membership), and with
        // no access it defers to the grant collector. It cannot reach a destructive verb — the seam has
        // none.
        //
        // Nothing is cancelled or reset here, and that is deliberate. In-flight transfers target the
        // device's event-independent byte partition (`/files/devices/<deviceId>/<filename>` — the eventId
        // never appears in the URL), so an upload in flight stays valid across a switch; cancelling it would
        // re-upload identical bytes to an identical URL. The ledger and cursor are device-global dedup and
        // are likewise left alone — the cycle's marker-gated reconciliation seeds already-stored resources
        // as COMPLETED and clears the cursor before any job is created (`event-rejoin-reconciliation`).
        uploadArm.onProvision()
        if (permission.permission.value == PermissionStatus.GRANTED) {
            // Create the event album now if opted in and permission is already granted (the grant
            // collector covers the grant-after-join case). Capability `event-album`.
            ensureAlbumIfOptedIn()
        }
        // Auto-download the other contributors' photos for this event (capability `photo-download`).
        // The reconcile is a no-op under an upload-only direction — gated inside the controller.
        scope.launch { downloadController.reconcile(cfg.eventId) }
        // Scan path: fill the title by id, best-effort, off the join (a failure leaves name empty).
        if (cfg.name.isEmpty()) scope.launch { fetchAndStoreName(cfg.eventId) }
    }

    /**
     * Best-effort fetch of the event name by id (`GET /events/:id`) and store it into the persisted
     * config — the scan-path name source and the foreground refresh. Non-throwing: a null (offline /
     * 404 / parse) leaves the current name unchanged.
     */
    private suspend fun fetchAndStoreName(eventId: String) {
        val fetched = (detailsSource.fetch(eventId) as? EventDetails.Found)?.name ?: return
        val current = config.config.value
        if (current?.eventId == eventId && current.name != fetched) {
            // Preserve the persisted cutoff — a name refresh must not clobber minPhotoDate (photo-selection-policy).
            config.save(current.copy(name = fetched))
        }
    }

    /**
     * Realize [launchEnvEventLinkApplied] once on first view creation (called from
     * [MainViewController]). Touching the `by lazy` runs the env read exactly once per process.
     */
    fun applyLaunchEnvEventLink() = log.invocation("applyLaunchEnvEventLink") {
        launchEnvEventLinkApplied
    }

    /**
     * Dev/test trigger: if a `SNAPSYNC_EVENT_LINK` process-environment variable is present, forward its
     * value through [onOpenUrl] exactly as a scanned QR would, provisioning the event headlessly over
     * USB. The variable is only injectable via a developer launch
     * (`pymobiledevice3 developer dvt launch --env …`); SpringBoard and TestFlight launches carry a
     * clean environment, so this is inert in production with no compile-time guard. Read **once per
     * process** (`by lazy`): a fresh cold launch with the variable still set re-provisions (the
     * intended per-build re-trigger); a mere view recreation within the same process does not.
     */
    private val launchEnvEventLinkApplied: Boolean by lazy {
        val raw = NSProcessInfo.processInfo.environment["SNAPSYNC_EVENT_LINK"] as? String
        if (raw != null) {
            log.i { "applying SNAPSYNC_EVENT_LINK launch-env event link" }
            onOpenUrl(raw)
        }
        true
    }

    /**
     * Realize [launchEnvSeedApplied] once on first view creation (called from [MainViewController]).
     */
    fun applyLaunchEnvSeed() = log.invocation("applyLaunchEnvSeed") {
        launchEnvSeedApplied
    }

    /**
     * Dev/test trigger: if `SNAPSYNC_SEED_PHOTOS` is present, fill the photo library with that many
     * synthetic assets (see [seedPhotoLibraryFromLaunchEnv]) so the capture-date-bounded walk can be
     * exercised against a large library on device. Like `SNAPSYNC_EVENT_LINK`, the variable is only
     * injectable via a developer launch, so this is inert in production.
     *
     * Seeding is a **blocking** `performChangesAndWait` loop, so it runs on `Dispatchers.Default`, never
     * this scope's `Dispatchers.Main` — the same reason the gallery walk hops off the main thread. The
     * `Logger.invocation` wrap is *inside* the launch so its context spans the async body.
     */
    private val launchEnvSeedApplied: Boolean by lazy {
        scope.launch(Dispatchers.Default) {
            log.invocation("seedPhotoLibrary") {
                seedPhotoLibraryFromLaunchEnv(log)
                // Seeding is blocking, so the probe below is sequenced INSIDE this launch — it must read a
                // library the seed has already committed, or it measures the wrong thing.
                runLaunchEnvPolicyProbe()
            }
        }
        true
    }

    /**
     * Dev/test trigger: `SNAPSYNC_POLICY_PROBE=<cutoff>` runs the **real** own-device status refresh against
     * that cutoff — the real `PhotoLibraryResourceEnumerator` (and so the real `PHFetchOptions` predicate),
     * the real origin rules, the real denylisted-album lookup — and logs the result
     * (capability `photo-selection-policy`).
     *
     * It exists because the policy is otherwise **unobservable on a device without a joined event**: the
     * status total only refreshes for a membership, and event *creation* is attest-gated, so there is no
     * headless route to one. But the policy's entire decision happens **before any HTTP call**, so a
     * membership is not actually needed to test it — only a cutoff. This gives the cutoff directly.
     *
     * What it proves, in one line of `debug.log`: the fetch predicate returns assets at all (the wrong
     * exclusion form returns **zero rows without raising**, which is the failure that would silently empty
     * the library), how many the origin rules excluded, and the resulting `N`. Pair with
     * `SNAPSYNC_SEED_POLICY`, whose assets straddle the resolution floor by construction.
     */
    private suspend fun runLaunchEnvPolicyProbe() {
        val cutoff = NSProcessInfo.processInfo.environment["SNAPSYNC_POLICY_PROBE"] as? String ?: return

        // Subtype census, on the RAW library (no exclusion predicate) — this is the part the status refresh
        // below cannot show, because the production predicate drops screenshots and screen recordings at the
        // fetch, so they never reach the count `refresh` reads. Here we look for them directly:
        //   - `total` is the whole library, so `total - enumerated(below)` is what the predicate dropped;
        //   - the two SELECT counts confirm the subtype bits actually match real, OS-generated assets — the
        //     one thing a synthesized library cannot prove (`PHAssetCreationRequest` cannot set a subtype).
        // The SELECT form `(mediaSubtypes & N) != 0` is used, NOT the exclusion form; both use the plural key.
        val total = PHAsset.fetchAssetsWithOptions(null).count.toLong()
        val screenshots = PHAsset.fetchAssetsWithOptions(
            PHFetchOptions().apply { predicate = NSPredicate.predicateWithFormat("(mediaSubtypes & 4) != 0", argumentArray = null) },
        ).count.toLong()
        val recordings = PHAsset.fetchAssetsWithOptions(
            PHFetchOptions().apply { predicate = NSPredicate.predicateWithFormat("(mediaSubtypes & 524288) != 0", argumentArray = null) },
        ).count.toLong()
        log.i {
            "policy probe: subtype census — library total=$total, screenshots=$screenshots, " +
                "screen-recordings=$recordings (these are what the fetch predicate drops before enumeration)"
        }

        log.i { "policy probe: refreshing the own-device total against cutoff=$cutoff" }
        // `Since` directly, NOT `Contribution.of(...)`: the probe runs with no membership by design (that is
        // the whole point — the policy is otherwise unobservable without a joined event), so there is no
        // direction to read. `None` would skip the walk and prove nothing; this probe exists to make the walk
        // happen and report what it found.
        gallery.refresh(Contribution.Since(cutoff))
        log.i { "policy probe: N=${gallery.size.value} (see the `gallery:` line above for the breakdown)" }
    }

    /**
     * Present the system share sheet (`UIActivityViewController`) carrying the invite link, from
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

    // Create the event album on the photo-permission grant (capability `event-album`): the app is the
    // sole creator, and sync needs the same grant, so the album exists before the first synced photo —
    // both processes then only ADD. Idempotent; a no-op when the membership opted out.
    private fun ensureAlbumOnGrant() {
        scope.launch {
            permission.permission.collect { status ->
                if (status == PermissionStatus.GRANTED) ensureAlbumIfOptedIn()
            }
        }
    }

    /**
     * Once photo access is full (`GRANTED`), start the upload producer through the tier-neutral arm
     * (capability `upload-lifecycle`) — which, on the OS-driven tier, means registering the extension so
     * the system can invoke its `process()`. The app itself performs no upload, fetch, enumeration, or
     * seed; the cycle self-reconciles, gated by the `joinedEventId` marker.
     *
     * This fires only on a **transition** to GRANTED (`permission` is a `StateFlow`, which conflates an
     * unchanged value — a foreground re-read of GRANTED→GRANTED does not re-emit). It therefore cannot
     * rescue a membership provisioned while access was *already* granted: [provisionEvent] owns that case.
     */
    private fun startUploadsOnGrant() {
        scope.launch {
            permission.permission.collect { status ->
                // Note this fires on the TRANSITION to granted (a StateFlow conflates an unchanged value),
                // so it can never rescue a membership provisioned while access was ALREADY granted —
                // `provisionEvent` owns that case. Assuming otherwise is what let the destructive provision
                // path look survivable.
                if (status == PermissionStatus.GRANTED) uploadArm.onPermissionGranted()
            }
        }
    }

    // The tier's upload mechanism, chosen ONCE (capability `upload-lifecycle`, "Exactly one producer per
    // process"). Both candidates are `by lazy`, so only the selected one is ever constructed — the
    // non-selected tier's mechanism cannot run. This is what makes the two tiers mutually exclusive
    // structurally rather than by a scattered runtime guard: `PhotoKitUploadProducer` simply does not
    // exist on the app-driven tier, so no code path (including the dev force flag, which previously walked
    // straight past the version guard and enabled BOTH tiers) can register the PhotoKit extension.
    private val uploadProducer: UploadProducer by lazy {
        if (useAppDrivenUpload) urlSessionUpload else photoKitProducer
    }

    // The tier-neutral lifecycle: which producer verb fires on which membership transition. Tested in
    // `:capability:upload` (JVM + iosSimulatorArm64) — the decision no longer lives in this untested shell.
    private val uploadArm: UploadArm by lazy {
        UploadArm(
            producer = uploadProducer,
            isGranted = { permission.permission.value == PermissionStatus.GRANTED },
            // A pure projection of the current membership — `null` when no event is joined. The root
            // defaults nothing: whether an absent membership arms the producer is a lifecycle DECISION,
            // and it lives in the tested `UploadArm`, not in this untested shell. A `?: true` here is what
            // previously started a producer for no event (capability `upload-lifecycle`, "No membership,
            // no arm").
            membershipIncludesUpload = { config.config.value?.direction?.includesUpload },
            log = log,
        )
    }

    // The OS-driven (≥26.1) mechanism. Never constructed on the app-driven tier.
    private val photoKitProducer: PhotoKitUploadProducer by lazy {
        PhotoKitUploadProducer(ledgerStore, log)
    }

    // Dev/test force flag (like SNAPSYNC_EVENT_LINK): forces the app-driven URLSession upload tier even on
    // iOS ≥26.1 so it can be exercised on a device or simulator that would otherwise run the extension.
    // Inert in production — a launch env var is only injectable via a developer launch.
    //
    // It selects the TIER and nothing else (`ios-url-session-upload`): it no longer doubles as "use a
    // foreground session". Conflating the two made the flag an unfaithful device-testing lever — the only
    // agent-driveable device (an SE2 on iOS 26.5) could not impersonate the app-driven tier, because
    // forcing it also downgraded the transport AND still registered the PhotoKit extension.
    private val forceUrlSessionUpload: Boolean =
        NSProcessInfo.processInfo.environment["SNAPSYNC_FORCE_URLSESSION_UPLOAD"] != null

    // Whether this process is a SIMULATOR — a FACT read from the environment, not inferred from a flag
    // someone has to remember to pass. A background `URLSession` runs on a device; the simulator is the
    // only place the transport is downgraded.
    private val isSimulator: Boolean =
        NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null

    /** True when uploads run in-process over a background URLSession (iOS 18–26.0, or the force flag). */
    private val useAppDrivenUpload: Boolean
        get() = !backgroundUploadSupported() || forceUrlSessionUpload

    // The app-driven (iOS 18–26.0) upload tier's composition root. Built lazily and used only when
    // [useAppDrivenUpload]; on iOS ≥26.1 without the force flag it is never touched (the extension runs).
    private val urlSessionUpload: UrlSessionUploadController by lazy {
        UrlSessionUploadController(
            scope, ledgerStore, config,
            // A supplier, not the resolved id: the cycle's gate probes it each run, so an unreadable
            // Keychain skips the cycle cleanly instead of throwing out of it. The lazy caches the first
            // success, so this is one read per process, as before.
            resolveDeviceId = { deviceId },
            host = backendHost, log = log,
            httpClient = http,
            // The app-driven tier performs its OWN uploads, so its request provider needs the token too.
            token = { attestation.token() },
            suppressedAssetIds = { downloadStore.suppressedLocalIds() },
            // Denylisted-album membership (capability `photo-selection-policy`). Supplied on THIS tier too:
            // both tiers funnel through the shared UploadCycle, and a policy wired on only one of them is
            // exactly the class of bug that shipped the app-driven tier without a re-join reconciler.
            albumExcludedAssetIds = { cutoff -> albumExcludedAssetIds(cutoff) },
            // A background session on any DEVICE — including a force-flagged one, which must be a faithful
            // proxy for the tier real 18–26.0 users run. Only the simulator is downgraded, and that is keyed
            // on actually being a simulator, not on the tier flag (`ios-url-session-upload`).
            useBackgroundSession = !isSimulator,
            // In-process liveness: after each pump cycle, re-read the ledger counts so status moves live.
            onCycleComplete = { ledgerCounts.refresh() },
            // Event album (capability `event-album`): add this cycle's completed own photos to the event
            // album (app tier, 18–26.0); raw localId recovered by reversing `_`→`/`.
            //
            // The event and the opt-in arrive from the cycle, which read them at its gate. This used to
            // re-read `config.config.value` here to recover both — a SECOND read, through the two-state
            // port that cannot express "unreadable", to re-derive facts the cycle already had.
            albumPlacement = { eventId, assetIds ->
                albumCoordinator.place(eventId, assetIds.map(::denormalizeAssetId))
            },
        )
    }

    /** The upload heartbeat BGProcessingTask handler (app-driven tier). Registered in the Swift shell. */
    fun runUploadHeartbeat(onComplete: () -> Unit) = log.invocation("runUploadHeartbeat") {
        if (useAppDrivenUpload) urlSessionUpload.onBackgroundTask(onComplete) else onComplete()
    }

    /** App short-version(build) for the boot banner (capability `diagnostic-logging`, D5). */
    private fun buildVersion(): String {
        val bundle = NSBundle.mainBundle
        val short = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?"
        val build = bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "?"
        return "$short($build)"
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
