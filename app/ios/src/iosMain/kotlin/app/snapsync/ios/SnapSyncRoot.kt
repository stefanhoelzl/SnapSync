package app.snapsync.ios

import app.snapsync.model.CompositionMode
import app.snapsync.model.EventConfig
import app.snapsync.model.LaunchDirectives
import app.snapsync.model.OsFacts
import app.snapsync.model.UploadTier
import app.snapsync.model.resolveComposition
import app.snapsync.compose.AppCore
import app.snapsync.compose.AppPorts
import app.snapsync.compose.snapSyncApp
import app.snapsync.config.FileBackedConfigStore
import app.snapsync.eventcreation.HttpEventCreation
import app.snapsync.attest.HttpAttestClient
import app.snapsync.attest.IosAttestKey
import app.snapsync.attest.KeychainAttestStore
import app.snapsync.join.HttpEnrollment
import app.snapsync.join.HttpEventDirectory
import app.snapsync.feature.membership.JoinOutcome
import app.snapsync.model.Contribution
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
import app.snapsync.model.PermissionStatus
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.MutableAttestedSource
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.forgeStatusHost
import app.snapsync.presentation.isForgeState
import app.snapsync.feature.membership.toJoinLoad
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.push.PushRegistration
import app.snapsync.time.SystemClock
import app.snapsync.time.SystemTimeZone
import app.snapsync.ports.PushTokenSource
import app.snapsync.membership.HttpLeaveNotifier
import app.snapsync.membership.darwinHttpClient
import app.snapsync.download.HttpEventUnionSource
import app.snapsync.download.IosDownloadTransport
import app.snapsync.model.DENYLISTED_ALBUM_TITLES
import app.snapsync.album.IosAlbumManager
import app.snapsync.album.IosAlbumMapStore
import app.snapsync.download.IosPhotoLibraryImporter
import app.snapsync.downloadstore.SqlDelightDownloadStore
import app.snapsync.downloadstore.iosDownloadStore
import platform.Foundation.NSFileManager
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.ports.LedgerStore
import app.snapsync.engine.iosLedgerStore
import app.snapsync.model.UPLOAD_LIVENESS_DARWIN_NAME
import app.snapsync.feature.upload.UploadProducer
import app.snapsync.logging.FileLogWriter
import app.snapsync.logging.appBuildVersion
import app.snapsync.logging.IosLogScope
import app.snapsync.logging.PublicNSLogWriter
import app.snapsync.keychain.KeychainDeviceIdentity
import app.snapsync.keychain.ProtectedDataGate
import app.snapsync.logging.invocation
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
 * Assembly is lazy so it runs once on first view creation, and it goes through the SHARED
 * composition (`snapSyncApp`, spec `module-architecture` "One shared composition"): this root
 * constructs the platform adapters and hands them as [AppPorts]; the feature graph — status
 * sources, attestation, join/leave/create, downloads, the upload arm — is composed in `:domain`'s
 * `compose/` zone as [app]. `permission` and `config` are each passed as both their ports (one
 * adapter implements both).
 *
 * **Upload lifecycle lives elsewhere.** This root selects exactly one [UploadProducer] for the process
 * (the PhotoKit extension registration on iOS ≥26.1, the in-app URLSession pump on 18–26.0) and forwards
 * membership transitions to the composed, tier-neutral `UploadArm` (`app.uploadArm`). The *decision* —
 * which verb fires on provision / grant / leave — is not made here, because this module is wiring-only and
 * untested by the project's hard rule, and parking that decision here is precisely how the app-driven tier
 * shipped a provision path that destroyed its ledger and started nothing (capability `upload-lifecycle`).
 */
object SnapSyncRoot {

    init {
        // Route kermit through a public NSLog writer AND a file writer. NSLog is redacted as
        // `<private>` on current iOS (dynamic format strings are private), so the file writer
        // (Documents/debug.log, pulled via `pymobiledevice3 apps pull`) is the reliable channel.
        // Both are consolidated in `:adapter:ios:ext-safe`; each line carries the ambient `[entryPoint]`.
        Logger.setLogWriters(PublicNSLogWriter(), FileLogWriter())
        // Boot banner (capability `diagnostic-logging`, D5) — names the process + build version so a
        // reader who concatenates the app/extension files can tell runs apart. `log` isn't assigned
        // yet in this init block, so use a fresh tagged logger.
        Logger.withTag("SnapSyncRoot").i { "=== app process start build=${appBuildVersion()} ===" }
    }

    private val log = Logger.withTag("SnapSyncRoot")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Launch directives → composition mode (spec `module-architecture`, "One shared composition") ──

    /** Every dev/test launch-environment trigger, parsed ONCE through the one typed surface
     *  (capability `ios-app-shell`). A production launch yields `LaunchDirectives.NONE`. */
    private val directives: LaunchDirectives =
        LaunchDirectives.from { name -> NSProcessInfo.processInfo.environment[name] as? String }

    /** OS facts the composition resolver reads — a simulator cannot run a background `URLSession`,
     *  and only iOS ≥26.1 carries the OS-driven background-upload API. */
    private val osFacts: OsFacts = OsFacts(
        backgroundUploadSupported = backgroundUploadSupported(),
        isSimulator = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null,
    )

    /**
     * The composition mode, resolved **once per process** by the pure, unit-tested resolver
     * (`model/CompositionMode.kt`). Forge excludes the live-stack boot — including an event link
     * (the shipped forge×link bug is now a resolver precedence test, not a shell guard).
     */
    private val mode: CompositionMode = resolveComposition(directives, osFacts, ::isForgeState)

    /**
     * **THE one switch on the resolved mode** (spec `module-architecture`, "One shared composition":
     * `composeRoot` switches once on the sealed type and invokes only the selected shell-supplied
     * adapter thunks). Everything mode- or tier-dependent — the render host, every OS entry point's
     * behavior, and the upload tier's mechanism thunks — is decided here, once; no entry point
     * re-checks a flag. Forge inertness is **structural**: [ForgeShell] holds no reference to [app]
     * or [host], so a forge launch cannot boot the live stack from any entry point (previously six
     * separate `isForging` guards, one of which was added only after the forge×link bug shipped).
     */
    private val shell: Shell = when (val m = mode) {
        is CompositionMode.Forge -> ForgeShell(m.state)
        is CompositionMode.Live -> when (m.tier) {
            // OS-driven PhotoKit tier (iOS ≥26.1): the OS owns upload scheduling — no foreground
            // pump, no upload push receiver (only the download arm wakes), no app-driven heartbeat.
            UploadTier.PHOTOKIT -> LiveShell(
                uploadProducer = { photoKitProducer },
                pumpForeground = {},
                uploadSilentPush = { null },
                heartbeat = { onComplete -> onComplete() },
            )
            // App-driven URLSession tier (iOS 18–26.0, or the dev force flag): the app process pumps.
            UploadTier.URL_SESSION -> LiveShell(
                uploadProducer = { urlSessionUpload },
                pumpForeground = { urlSessionUpload.onForeground() },
                uploadSilentPush = { urlSessionUpload.pushReceiver::onSilentPush },
                heartbeat = { onComplete -> urlSessionUpload.onBackgroundTask(onComplete) },
            )
        }
    }

    /** BGTaskScheduler identifier for the download import-tail backstop — MUST match the Swift host's
     * `register(forTaskWithIdentifier:)` and the Info.plist `BGTaskSchedulerPermittedIdentifiers`. */
    const val DOWNLOAD_BACKSTOP_TASK_ID: String = "app.snapsync.download.backstop"

    // The event config seam/store (one file-backed adapter is both — App-Group file of record with
    // Keychain write-through, migration step 11a), hoisted so a (re)provision can read the current
    // event id and the leave use-case can clear it.
    private val config: FileBackedConfigStore by lazy { FileBackedConfigStore() }

    /**
     * The one cutoff formatter every surface shares (capability `photo-selection-policy`; migration
     * step 9): presentation's `CutoffFormatter` is pure given its inputs, so this root binds the
     * `Clock`/`TimeZoneSource` ports' system adapters here — wiring, not a decision. Deliberately NOT
     * seated on [AppCore]: the forge composition renders the create screen's wall clock too, and it
     * must reach a formatter without any route to the live graph.
     */
    val cutoffFormatter: CutoffFormatter by lazy {
        CutoffFormatter(now = SystemClock::now, zone = SystemTimeZone.current())
    }

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
    // the PhotoKit manager — the two adapters the composed coordinator (`app.albumCoordinator`) sits on.
    // Hoisted: the selection policy also reads the manager directly (denylisted-album membership), and
    // the atomic import-time album lookup reads the map (capability `photo-selection-policy`).
    private val albumMapStore: IosAlbumMapStore by lazy { IosAlbumMapStore() }
    private val albumManager: IosAlbumManager by lazy { IosAlbumManager() }

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

    // The photo-library permission adapter, hoisted so the grant collector and a (re)provision share one
    // instance (both enable the extension; a provision must re-enable a producer a prior leave disabled).
    private val permission: PhotoLibraryPermission by lazy { PhotoLibraryPermission() }

    // The stable per-install device id (shared Keychain — the SAME item the extension reads): the
    // `/files/devices/<deviceId>/` partition the app's status lists. (Finishes wiring `device-identity` into
    // both roots.)
    private val deviceId: String by lazy { KeychainDeviceIdentity().deviceId() }

    /**
     * The composed app graph (spec `module-architecture`, "One shared composition"): this root
     * constructs the platform adapters and coordination lambdas as [AppPorts]; `snapSyncApp`
     * composes the features. Every [AppCore] property is `by lazy`, so first-touch construction
     * timing matches the lazy web that used to live here — nothing resolves the device identity or
     * opens a protected store earlier than before (the locked-background-launch property).
     *
     * The attest client's own HTTP client is deliberately UNauthenticated: the three `/attest/…`
     * routes are the ones that issue the token, so authenticating them would be a cycle.
     */
    private val app: AppCore by lazy {
        // Only [LiveShell] entry points ever reach this graph — [ForgeShell] has no route here — so
        // the tier thunks resolve through the one mode switch; the cast documents (and enforces,
        // loudly) that a forge launch composes no live core.
        val live = shell as LiveShell
        snapSyncApp(
            scope,
            AppPorts(
                configSource = config,
                configStore = config,
                photoAccess = permission,
                // The same adapter serves the status source and the request/Settings surface — the
                // bundle's requestAccess/openSettings commands bind to it in `compose/`.
                photoAccessRequester = permission,
                photoLibrary = PhotoLibraryResourceEnumerator(),
                ledger = ledgerStore,
                downloadStore = downloadStore,
                // The importer writes createdLocalId synchronously from inside a PhotoKit change
                // block (concrete store, not the port) and borrows the atomic album-add lookup.
                importer = IosPhotoLibraryImporter(
                    recordCreatedLocalId = { ref, id -> downloadStore.recordCreatedLocalId(ref, id) },
                    // The atomic import-time album lookup: the membership's opt-in gate is the
                    // coordinator's rule (capability `event-album`); this thunk only reads the
                    // current membership's facts. Deferred — it runs inside a PhotoKit change block,
                    // long after this graph is constructed.
                    albumId = {
                        config.config.value?.let { cfg ->
                            app.albumCoordinator.albumIdFor(cfg.eventId, cfg.saveToAlbum)
                        }
                    },
                ),
                downloadStagingRoot = {
                    val container = NSFileManager.defaultManager
                        .containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)?.path
                        ?: error("App Group container '$LEDGER_APP_GROUP' unavailable")
                    "$container/download-staging"
                },
                newDownloadTransport = { host -> IosDownloadTransport(host) },
                union = HttpEventUnionSource(http, backendHost),
                directory = detailsSource,
                enrollment = HttpEnrollment(http, backendHost),
                eventCreation = HttpEventCreation(http, backendHost),
                attestKey = IosAttestKey(),
                attestClient = HttpAttestClient(darwinHttpClient(), backendHost),
                attestStore = KeychainAttestStore(),
                deviceId = { deviceId },
                now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
                // The tier's mechanism, selected ONCE per process by the mode switch above
                // (capability `upload-lifecycle`): exactly one producer, the other never constructed.
                uploadProducer = live.uploadProducer,
                albumManager = albumManager,
                albumMapStore = albumMapStore,
                albumExcludedAssetIds = { cutoff -> albumExcludedAssetIds(cutoff) },
                notifyLeave = { eventId -> leaveNotifier.leave(eventId, deviceId) },
                // Coordination is the `flow/` zone's (step 8); this root supplies the provision flow's
                // entry (a thin log-wrapped delegator) and the shell/platform effect lambdas the flows
                // coordinate over — each a port/platform touch a flow may not make directly.
                provision = ::provisionEvent,
                onEventMinted = { eventId -> host.onEventCreated(eventId) },
                refreshAttestation = ::refreshAttestation,
                protectedDataGate = { tag, work -> protectedData.runWhenAvailable(tag, work) },
                pumpForeground = live.pumpForeground,
                unregisterLiveness = ::unregisterLivenessObserver,
                scheduleBackstop = ::scheduleDownloadBackstop,
                // The platform half of the share command (a system sheet over the top view controller).
                share = ::presentShareSheet,
                // The upload arm's push receiver on the app-driven tier (a thunk — the tier controller
                // depends on this graph, so it must resolve lazily); null on iOS ≥26.1.
                uploadSilentPush = live.uploadSilentPush,
                log = log,
                // Drive the shared iOS ambient log context (the process-global the device-log writers
                // read) so the tier-neutral features' lines carry the triggering entry point's prefix.
                logScope = IosLogScope,
            ),
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
            token = { app.attestation.token() },
            // Rejected (not merely expired) → drop it and go get a new one right now. We are demonstrably
            // online (the backend just answered), so this is the best possible moment to recover.
            onRejected = {
                app.attestation.onRejected()
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
            val ok = runCatching { app.attestation.ensureFresh() }.getOrDefault(false)
            // Surface it ONLY when we both lack a usable token and could not get one. A stale token that
            // renews is a non-event; raising it would be noise. And because opening the app IS a wake, this
            // normally clears before it can be seen — what survives is a device that is offline or being
            // refused, which is a real problem that no amount of waiting fixes.
            attested.set(ok || !app.attestation.isStale(app.attestation.token()))
        }
    }

    /** Drives `SyncHealth.Unattested` (capability `device-attestation`). See [refreshAttestation]. */
    private val attested = MutableAttestedSource()

    // The app-side handle on the extension's shared App-Group ledger, used for two narrow things only:
    // a READ-ONLY aggregates read (`completed`/`pending`, via the composed counts source) and a
    // reset-family `clearRequested()` on extension disable (recover jobs the disable wiped, capability
    // `ios-background-upload`). No per-key record writes here — on the OS-driven tier the extension
    // stays the sole record writer. WAL permits the concurrent cross-process read.
    private val ledgerStore: LedgerStore by lazy { iosLedgerStore() }

    // --- Photo download / import (capability `photo-download`) ---
    // The app-written download store (idempotency + per-resource staging + the createdLocalId the
    // extension reads as its suppression set). Concrete type so the importer can write createdLocalId
    // synchronously from inside a PhotoKit change block.
    private val downloadStore: SqlDelightDownloadStore by lazy { iosDownloadStore() }

    // The seam that tells the backend this device is leaving (DELETE /events/<id>/devices/<id>,
    // capability `event-leave-endpoint`) — the backend renames the manifest to its departed
    // `.left.json` sibling and reaps/GCs the event when the last member leaves. Best-effort (a failed
    // call never blocks leaving). Used by BOTH the explicit Leave and a switch (see [provisionEvent]).
    private val leaveNotifier: HttpLeaveNotifier by lazy { HttpLeaveNotifier(http, backendHost) }

    // The device-facing backend host (baked at compile time); shared by every generic HTTP adapter
    // handed to the composed graph and the event-metadata (name) fetch.
    private val backendHost: String by lazy {
        NSBundle.mainBundle.objectForInfoDictionaryKey("BackgroundUploadURLBase") as? String ?: ""
    }

    // The ONE GET /events/:id client (capability `join-event`): the join gate's details fetch and the
    // best-effort scan-path/foreground name refresh both read through it — the latter via the
    // `EventDirectory` port in [AppPorts] (`directory`), whose fetch effect `compose/` builds for the
    // Foreground/Provision flows.
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

    // The silent-push cross-arm fan-out (a push means "the event changed": foreign photos to pull, and —
    // since the event is live — a good moment to contribute our own) is now the `flow/SilentPush` trigger.
    // This root supplies its arms: the download arm's receiver is composed in the app graph, and the upload
    // arm's rides the `uploadSilentPush` thunk in [AppPorts] (app-driven tier only; null on iOS ≥26.1).

    val host: StatusContainerHost by lazy {
        // The own-device source: ledger completeness + in-flight (one aggregates() read) × permission ×
        // the live own-device gallery total, minted into snapshots — composed in the app graph.
        // Ledger-sourced, no storage LIST for upload status (spec: sync-status).
        val syncSource = app.syncStatusSource
        // Install the permission-grant subscriptions (upload-arm start + album ensure) — the collectors
        // live in `compose/` (`AppCore.installPermissionSubscriptions`), but they install ONLY from this
        // host-assembly path, exactly where the pre-step-8 `startUploadsOnGrant` / `ensureAlbumOnGrant`
        // calls sat: a cold backstop/URLSession wake that merely touches [app] must not fire a
        // producer-start off the permission StateFlow's replay.
        app.installPermissionSubscriptions()
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
            runCatching { app.attestation.ensureFresh() }
            pushRegistration.run(pushTokenSource, app.attestation.tokenChanged)
        }
        // The host observes the adapters' read-model StateFlows directly (migration step 9's split:
        // presentation names no ports — the Keychain/PhotoKit adapters stay behind their flows).
        // No EventStatus source: status is read from the listing; the extension owns reconciliation.
        StatusContainerHost(
            syncSource, permission.permission, config.config, scope,
            creationStatusSource = app.creationStatus,
            // The user-tap command bundle (leave / create / commitJoin / share / requestAccess /
            // openSettings), built and decorated only in `compose/` (`AppCore.userCommands`) —
            // presentation fires commands solely through it (spec `module-architecture`, "Commands
            // cross one door").
            commands = app.userCommands,
            // The join gate's details READ (capability `join-event`): a scanned QR opens the
            // confirmation; details are fetched (GET) and mapped by feature/membership's [toJoinLoad].
            loadJoinDetails = { eventId -> app.joinEvent.loadDetails(eventId).toJoinLoad() },
            cutoffFormatter = cutoffFormatter,
            log = { message -> log.i { message } },
            downloadSource = app.downloadStatusSource,
            attestedSource = attested,
        )
    }

    /**
     * The host [MainViewController] renders. Resolved **once per process** (`by lazy`) through the one
     * mode switch: the forged host in [CompositionMode.Forge] (capability `ios-app-shell` — a marketing
     * screenshot renders forged sources and MUST NOT boot the live stack: the unsigned simulator the
     * screenshots run in has no App-Group ledger container, no App Attest, no PhotoKit grant, and no
     * backend), else the live [host].
     */
    val renderHost: StatusContainerHost by lazy { shell.renderHost() }

    /**
     * The SwiftUI scene's foreground transition (forwarded from the `@main` scene's scenePhase):
     * re-read the per-device file listing (completeness) and the ledger's in-flight count so status
     * reflects any uploads that progressed while backgrounded (capability `sync-status` liveness).
     * Delegated through the one mode switch — every OS entry point below is a thin pass-through to
     * [shell]; the forge/live decision was made once, at resolve time.
     */
    fun onForeground() = shell.onForeground()

    /**
     * On backgrounding, queue the download import-tail backstop so any staged-but-unimported foreign
     * assets get imported at the next idle/charging window even if no further download wakes the app
     * (capability `photo-download`, 5.4). Status liveness itself stays event-driven (foreground entry).
     */
    fun onBackground() = shell.onBackground()

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
        scope.launch { app.ledgerCounts.refresh() }
    }

    /**
     * The `BGProcessingTask` import-tail backstop (capability `photo-download`, 5.4): drains any
     * staged-but-not-yet-imported foreign assets when no further download event would wake the app
     * (e.g. the last transfer overran its URLSession wake budget). OS-scheduled (idle/charging) via the
     * Swift host's `BGTaskScheduler` registration; [onComplete] maps to `task.setTaskCompleted`.
     * Discovery stays foreground-only — this imports already-downloaded work, it does not re-read the union.
     */
    fun runDownloadBackstop(onComplete: () -> Unit) = shell.runDownloadBackstop(onComplete)

    /** Queue a `BGProcessingTask` request so the OS runs [runDownloadBackstop] at a future idle moment. */
    @OptIn(ExperimentalForeignApi::class)
    fun scheduleDownloadBackstop() {
        val request = BGProcessingTaskRequest(DOWNLOAD_BACKSTOP_TASK_ID)
        request.requiresNetworkConnectivity = false // imports operate on already-staged bytes
        request.requiresExternalPower = false
        runCatching { BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null) }
            .onFailure { log.w(it) { "could not schedule download backstop" } }
    }

    /**
     * The system relaunched the app to finish background `URLSession` events (the Swift app delegate's
     * `handleEventsForBackgroundURLSession` seam): the app-driven upload session (iOS 18–26.0) or the
     * download session, routed by identifier inside the live shell.
     */
    fun handleBackgroundUrlSession(identifier: String, completionHandler: () -> Unit) =
        shell.handleBackgroundUrlSession(identifier, completionHandler)

    /**
     * An event link arrived (forwarded raw from the Swift entry point — the complete URL, fragment
     * included, since the fragment carries the whole payload). Routed straight to
     * the container's **join gate** (capability `join-event`): it decodes, and either opens the
     * confirmation (a first join → full-screen; a different event while joined → switch dialog),
     * auto-confirms when the link carries `autoJoin=true` (the dev/headless trigger), or flashes the
     * invalid-link error. The app no longer provisions directly on scan — the gate owns that.
     */
    fun onOpenUrl(url: String) = shell.onOpenUrl(url)

    /**
     * The OS delivered an APNs device token (capability `push-registration`), forwarded raw-hex from the
     * Swift AppDelegate's `didRegisterForRemoteNotificationsWithDeviceToken`. Feed it to the token
     * source; the registration collector PUTs `devices/<id>/config`. Idempotent across launches and
     * rotations. Touch [host] so the collector is running to observe it. No decision in Swift.
     */
    fun onPushToken(hex: String) = shell.onPushToken(hex)

    /**
     * A silent (`content-available`) remote notification arrived (capability `push-registration`),
     * forwarded from the Swift AppDelegate with the push payload's `eventId`. Route it to the receiver,
     * which — if [eventId] is the active event — reconciles downloads (union read + enqueue). We hold the
     * OS [completion] handler until the receiver's synchronous work finishes, so iOS keeps the app alive
     * through the enqueue (the background transfers then continue on their own). Touch [host] so the
     * download stack is assembled on a background launch. Non-throwing: a failure still calls [completion].
     */
    fun onSilentPush(eventId: String, completion: () -> Unit) = shell.onSilentPush(eventId, completion)

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
        // The switch-leave → save → refresh → arm → album → reconcile → name coordination is the
        // `flow/Provision` trigger's; this thin wrapper keeps only the entry-point log context (over
        // IosLogScope) so the flow's synchronous steps carry `[provisionEvent]` and its escaping launches
        // (reconcile / name) self-label, exactly as before. Nothing here destructures the config (a
        // newly-added field must not be dropped before the save the extension reads).
        app.provisionFlow.run(cfg)
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
        directives.eventLink?.let { raw ->
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
        val cutoff = directives.policyProbe ?: return

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
        app.gallery.refresh(Contribution.Since(cutoff))
        log.i { "policy probe: N=${app.gallery.size.value} (see the `gallery:` line above for the breakdown)" }
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

    // The permission-grant subscriptions (upload-arm start + event-album ensure) live in `compose/`
    // (`AppCore.installPermissionSubscriptions`, migration step 8) and are installed ONLY from the
    // [host] assembly above — never on mere [AppCore] construction, so a cold background wake starts
    // no producer off the StateFlow's replay. Both fire only on a *transition* to GRANTED, so neither
    // can rescue a membership provisioned while access was already granted — the provision flow owns
    // that case.

    // The upload tier's two candidate mechanisms. Both are `by lazy`, and only the tier the mode
    // switch selected ever *thunks* one into the composed graph — `PhotoKitUploadProducer` simply is
    // not constructed on the app-driven tier, so no code path (including the dev force flag, which
    // previously walked straight past the version guard and enabled BOTH tiers) can register the
    // PhotoKit extension. (The tier-neutral `UploadArm` — which verb fires on which membership
    // transition — is composed in the app graph as `app.uploadArm`, over the selected thunk.)
    private val photoKitProducer: PhotoKitUploadProducer by lazy {
        PhotoKitUploadProducer(ledgerStore, log)
    }

    // The app-driven (iOS 18–26.0) upload tier's composition root. Built lazily; reached only through
    // the URL_SESSION branch of the mode switch — plus the background-session drain, which may adopt an
    // old upload session on any live tier. On iOS ≥26.1 without the force flag it is otherwise never
    // touched (the extension runs).
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
            token = { app.attestation.token() },
            // Echo-suppression: the concrete store IS the narrowed SuppressionSource port.
            suppression = downloadStore,
            // Denylisted-album membership (capability `photo-selection-policy`). Supplied on THIS tier too:
            // both tiers funnel through the shared UploadCycle, and a policy wired on only one of them is
            // exactly the class of bug that shipped the app-driven tier without a re-join reconciler.
            albumExcludedAssetIds = { cutoff -> albumExcludedAssetIds(cutoff) },
            // A background session on any DEVICE — including a force-flagged one, which must be a faithful
            // proxy for the tier real 18–26.0 users run. Only the simulator is downgraded — the fact is
            // resolved once on [CompositionMode.Live], never re-derived (`ios-url-session-upload`). The
            // cast holds on both live tiers (a PhotoKit-tier process can still be relaunched to drain an
            // old upload session); in forge mode this controller is unreachable.
            useBackgroundSession = (mode as CompositionMode.Live).useBackgroundSession,
            // In-process liveness: after each pump cycle, re-read the ledger counts so status moves live.
            onCycleComplete = { app.ledgerCounts.refresh() },
            // Event album (capability `event-album`): the composed coordinator; the cycle applies the
            // membership's opt-in (which arrived with its gate) and `uploadCore` owns the shared
            // `assetId` denormalization.
            albumCoordinator = app.albumCoordinator,
        )
    }

    /** The upload heartbeat BGProcessingTask handler (app-driven tier). Registered in the Swift shell. */
    fun runUploadHeartbeat(onComplete: () -> Unit) = shell.runUploadHeartbeat(onComplete)

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

    /** The `onForeground` invocation params, byte-compatible with the pre-C3 wording; the values now
     *  read from the resolved mode + parsed directives instead of being re-derived per call. */
    private fun foregroundParams(): String {
        val appDriven = (mode as? CompositionMode.Live)?.tier == UploadTier.URL_SESSION
        return "useAppDrivenUpload=$appDriven force=${directives.forceUrlSessionUpload} " +
            "osSupported=${osFacts.backgroundUploadSupported}"
    }

    // ── The mode-resolved shell delegate (the target of THE one switch above) ────────────────────

    /** What every OS entry point delegates to; implemented once per composition mode. */
    private interface Shell {
        fun renderHost(): StatusContainerHost
        fun onForeground()
        fun onBackground()
        fun onOpenUrl(url: String)
        fun onPushToken(hex: String)
        fun onSilentPush(eventId: String, completion: () -> Unit)
        fun runUploadHeartbeat(onComplete: () -> Unit)
        fun runDownloadBackstop(onComplete: () -> Unit)
        fun handleBackgroundUrlSession(identifier: String, completionHandler: () -> Unit)
    }

    /**
     * The forge composition (capability `ios-app-shell`): render the shared screen over forged sources
     * for a marketing screenshot and assemble NO live stack — the unsigned simulator the screenshots
     * run in has no App-Group ledger container, no App Attest, no PhotoKit grant, and no backend.
     * This class holds no reference to [app] or [host], so forge inertness is **structural** rather
     * than guarded. OS completion handlers are still invoked — they are the OS's, and an unanswered
     * one costs the app its future background wakes; everything else logs and returns.
     */
    private class ForgeShell(private val state: String) : Shell {
        override fun renderHost(): StatusContainerHost {
            log.i { "rendering SNAPSYNC_FORGE_STATE=$state" }
            // Non-null by construction: the resolver only yields Forge for a recognized state.
            return forgeStatusHost(state, scope, cutoffFormatter)!!
        }

        override fun onForeground() = log.invocation("onForeground", params = foregroundParams()) {
            log.i { "forge mode: skipping live foreground work" }
        }

        override fun onBackground() = log.invocation("onBackground") {}

        override fun onOpenUrl(url: String) = log.invocation("onOpenUrl", params = "url=$url") {
            // A screenshot run may also carry `SNAPSYNC_EVENT_LINK`; provisioning a real event from a
            // process rendering a forged frame is incoherent before it is a crash. The resolver's
            // precedence already excludes it (the forge×link bug, now a unit test); the log line keeps
            // the debug.log trail.
            log.i { "forge mode: ignoring event link" }
        }

        override fun onPushToken(hex: String) = log.invocation("onPushToken", params = "hex=${hex.take(12)}…") {
            // `registerForRemoteNotifications()` is called unconditionally at launch, so this arrives
            // on a screenshot run too. Registering a token for a process that exists only to render
            // one frame buys nothing, so drop it.
            log.i { "forge mode: ignoring push token" }
        }

        override fun onSilentPush(eventId: String, completion: () -> Unit) {
            // [completion] is still invoked, and that is not optional: an unanswered
            // `content-available` push costs the app its future background wakes.
            log.i { "forge mode: ignoring silent push for $eventId" }
            completion()
        }

        override fun runUploadHeartbeat(onComplete: () -> Unit) = log.invocation("runUploadHeartbeat") {
            onComplete()
        }

        override fun runDownloadBackstop(onComplete: () -> Unit) {
            log.i { "forge mode: ignoring download backstop" }
            onComplete()
        }

        override fun handleBackgroundUrlSession(identifier: String, completionHandler: () -> Unit) {
            log.i { "forge mode: ignoring background URLSession events for $identifier" }
            completionHandler()
        }
    }

    /**
     * The live composition: the real stack, on the tier the mode switch selected. The four
     * constructor thunks are the ONLY tier-dependent seams — each decided once at the switch, so no
     * method here re-checks a flag; the bodies are the former entry-point bodies, verbatim, minus
     * their forge guards.
     */
    private class LiveShell(
        /** The selected tier's upload mechanism (capability `upload-lifecycle`), thunked into [AppPorts]. */
        val uploadProducer: () -> UploadProducer,
        /** Foreground pump (app-driven tier); `{}` on iOS ≥26.1 where the OS owns scheduling. */
        val pumpForeground: () -> Unit,
        /** The upload arm's silent-push receiver (app-driven tier); `{ null }` on iOS ≥26.1. */
        val uploadSilentPush: () -> (suspend (eventId: String) -> Unit)?,
        /** The BGProcessingTask heartbeat handler (app-driven tier); completes immediately on ≥26.1. */
        private val heartbeat: (onComplete: () -> Unit) -> Unit,
    ) : Shell {
        /** Touching [host] assembles the live stack (and installs the grant subscriptions). */
        override fun renderHost(): StatusContainerHost = host

        override fun onForeground() = log.invocation("onForeground", params = foregroundParams()) {
            host
            // Listen for the extension's cross-process liveness ding while foreground, so upload status
            // moves live as the extension records completions/new jobs (spec: sync-status).
            // Foreground-only: a suspended app cannot act on the post. The `CFNotificationCenter`
            // registration is platform, so it stays shell-local; the rest of the foreground coordination
            // (pump / refresh / reconcile / name / attestation, ordered, its launches escaping this
            // entry's synchronous span) is the flow's.
            registerLivenessObserver()
            app.foregroundFlow.run()
        }

        override fun onBackground() = log.invocation("onBackground") {
            // Stop the liveness observer + arm the backstop — the `flow/Background` trigger's coordination.
            app.backgroundFlow.run()
            log.i { "=== app entering background ===" }
        }

        override fun onOpenUrl(url: String) {
            log.invocation("onOpenUrl", params = "url=$url") {
                host.onOpenUrl(url)
            }
        }

        override fun onPushToken(hex: String) = log.invocation("onPushToken", params = "hex=${hex.take(12)}…") {
            // Touch [host] so the registration collector is running to observe it. No decision in Swift.
            host
            pushTokenSource.deliver(hex)
        }

        override fun onSilentPush(eventId: String, completion: () -> Unit) {
            host
            scope.launch {
                // Wrap INSIDE the launch so `[onSilentPush]` spans the async reconcile (and the download
                // HTTP + import lines it drives trace back to this push). The gate → attestation →
                // cross-arm fan-out coordination is the `flow/SilentPush` trigger's (it absorbed
                // FanOutPushReceiver); only the entry-point wrap and the OS completion handler stay
                // shell-local.
                log.invocation(
                    "onSilentPush",
                    params = "eventId=$eventId protectedData=${protectedData.isAvailable()}",
                ) {
                    app.silentPushFlow.run(eventId)
                }
                // Always release the OS handler promptly — iOS gives a silent push a short budget and
                // holding it open until an unlock would simply get us killed.
                completion()
            }
        }

        override fun runUploadHeartbeat(onComplete: () -> Unit) = log.invocation("runUploadHeartbeat") {
            heartbeat(onComplete)
        }

        override fun runDownloadBackstop(onComplete: () -> Unit) {
            scope.launch {
                // Wrap INSIDE the launch so `[runDownloadBackstop]` spans the async import. The
                // protected-data state rides the entry-point line (capability `ios-app-shell`): a
                // background wake on a locked device is otherwise invisible, and it is the only place
                // this class of bug shows up — no test can reach it. The gate/attestation/import
                // coordination is the `flow/DownloadBackstop` trigger's; only the entry-point wrap and
                // re-arm stay shell-local.
                log.invocation("runDownloadBackstop", params = "protectedData=${protectedData.isAvailable()}") {
                    app.downloadBackstopFlow.run()
                }
                // Re-arm for the next idle window and release the OS's task assertion immediately — even
                // when the work above was deferred, since holding the BGTask open until an unlock is not
                // an option.
                scheduleDownloadBackstop()
                onComplete()
            }
        }

        override fun handleBackgroundUrlSession(identifier: String, completionHandler: () -> Unit) = log.invocation(
            "handleBackgroundUrlSession",
            params = "identifier=$identifier protectedData=${protectedData.isAvailable()}",
        ) {
            // Route by session identifier: the app-driven UPLOAD session (18–26.0) vs the download
            // session. A wiring-forced routing decision: one OS callback serves two distinct sessions.
            if (identifier == UrlSessionUploadController.SESSION_IDENTIFIER) {
                urlSessionUpload.onBackgroundSessionEvents(completionHandler)
                return@invocation
            }
            // Downloads: the OS relaunched us to deliver background download completions. Adopt the
            // session so its delegate fires (staging + import run), and invoke the OS handler once
            // events drain.
            app.downloadJobs.adoptBackgroundEvents(completionHandler)
        }
    }
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
