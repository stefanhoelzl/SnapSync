package app.snapsync.ios

import app.snapsync.model.EventConfig
import app.snapsync.model.SceneMode
import app.snapsync.model.appVisibilityFrom
import app.snapsync.model.resolveScene
import app.snapsync.compose.AppCore
import app.snapsync.compose.AppPorts
import app.snapsync.compose.snapSyncApp
import app.snapsync.config.FileBackedConfigStore
import app.snapsync.config.bakedApnsEnv
import app.snapsync.eventcreation.HttpEventCreation
import app.snapsync.eventcreation.HttpEventRename
import app.snapsync.attest.HttpAttestClient
import app.snapsync.attest.IosAttestKey
import app.snapsync.attest.KeychainAttestStore
import app.snapsync.join.HttpEnrollment
import app.snapsync.join.HttpEventDirectory
import app.snapsync.feature.membership.JoinOutcome
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.toFacts
import app.snapsync.gallery.IosDeviceManifestStore
import app.snapsync.gallery.PhotoKitCandidateSource
import app.snapsync.ios.discovery.IosDiscoveryStore
import app.snapsync.model.PermissionStatus
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.permission.PhotoSelectionSnapshotSource
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.feature.membership.toJoinLoad
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.feature.push.ApnsPushToken
import app.snapsync.feature.push.PushRegistration
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
import app.snapsync.download.IosStagedBytes
import app.snapsync.download.PhotoKitAssetPresence
import app.snapsync.share.IosShareSheet
import app.snapsync.downloadstore.SqlDelightDownloadStore
import app.snapsync.downloadstore.iosDownloadStore
import app.snapsync.feature.upload.UploadMechanismRuntime
import app.snapsync.model.UploadMechanism
import app.snapsync.model.resolveUploadMechanism
import app.snapsync.ports.OsReceipt
import app.snapsync.ports.ReceiptDeadlines
import app.snapsync.ports.LedgerStore
import app.snapsync.config.bakedUploadBase
import app.snapsync.engine.iosLedgerStore
import app.snapsync.model.EventLinkDelivery
import app.snapsync.model.PlatformEntry
import app.snapsync.link.isWebLinkActivity
import app.snapsync.model.forwardEventLink
import app.snapsync.model.userActivityParams
import app.snapsync.feature.upload.UploadProducer
import app.snapsync.logging.FileLogWriter
import app.snapsync.logging.appLogDestination
import app.snapsync.logging.IosDeviceLogSource
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.logging.SentryDiagnosticsReporter
import app.snapsync.logging.appBuildVersion
import app.snapsync.logging.IosLogScope
import app.snapsync.logging.PublicNSLogWriter
import app.snapsync.keychain.DeviceIdentityRole
import app.snapsync.keychain.KeychainDeviceIdentity
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserActivity
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSPredicate
import platform.Foundation.NSProcessInfo
import platform.Photos.PHAsset
import platform.Photos.PHFetchOptions
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification

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
        // The app's log stays in its OWN Documents — it can read it without help, so relocating it
        // would break every pull command and buy nothing (capability `diagnostic-logging`).
        Logger.setLogWriters(PublicNSLogWriter(), FileLogWriter(appLogDestination().path))
        // Boot banner (capability `diagnostic-logging`, D5) — names the process + build version so a
        // reader who concatenates the app/extension files can tell runs apart. `log` isn't assigned
        // yet in this init block, so use a fresh tagged logger.
        Logger.withTag("SnapSyncRoot").i { "=== app process start build=${appBuildVersion()} ===" }
        // The BAKED backend this build talks to. Cheap (one Info.plist read) and it names the one fact
        // that makes an otherwise-silent failure legible: point a build at a different backend without
        // a device reset and the ledger still says COMPLETED, so the device uploads nothing —
        // no error, no failed request. Read together with the cycle's own
        // `enumeration: N seen, X new, Y already-uploaded`, a changed host beside an unchanged ledger
        // names the cause immediately. Diagnostic only: no behaviour, no state, no extra I/O.
        Logger.withTag("SnapSyncRoot").i { "[boot] upload base = ${bakedUploadBase()}" }
    }

    private val log = Logger.withTag("SnapSyncRoot")

    // The app-scope error boundary. Without a handler, an uncaught throwable from any `scope.launch`
    // hits Kotlin/Native's default terminate → SIGABRT — a background failure (a platform-API call, an
    // App-Group read, a deprecated PhotoKit selector on a newer iOS) takes the whole app down at launch.
    // The `SupervisorJob` already isolates SIBLING coroutines from each other's failures; this makes an
    // otherwise-unhandled failure land in `debug.log` (the un-redacted channel) instead of aborting the
    // process, honouring the rule that errors reduce into state and never crash the shell. Every feature
    // reduces its own domain errors into `UiState`; this catches only what nothing else did.
    private val scope = CoroutineScope(
        SupervisorJob() + compositionLane +
            CoroutineExceptionHandler { _, t ->
                log.e(t) { "uncaught in app scope — logged, not fatal" }
            },
    )

    // ── The OS fact → upload-mechanism PRESENCE (spec `module-architecture`, "One shared composition") ──

    // `internal` (not `private`) for the same single reason as [app]: the rig's contributed hook reports
    // this OS fact on `/device/state`, and reading the value the app actually resolved is the only
    // way to report it without a second resolution that could disagree. `internal` is module-wide and is
    // not exported to the `SnapSyncKit` ObjC header.
    /**
     * Whether this OS carries the OS-driven upload mechanism at all (iOS ≥26.1).
     *
     * This is the whole of what the shell still decides about uploads. It is **presence**, not behaviour:
     * which mechanism *runs* is resolved from this fact plus the current permission plus any development
     * override, by the pure `resolveUploadMechanism` in `:domain model/`, re-evaluated on every transition
     * — because the OS never invokes the extension under a partial grant, so the mechanism genuinely
     * changes when permission does, and a once-per-process answer could not say that.
     */
    internal val osSupportsOsDrivenUpload: Boolean = backgroundUploadSupported()

    /**
     * Where a development pin on the upload mechanism comes from (capability `upload-lifecycle`).
     *
     * **A shipped build cannot carry one, and that is structural rather than probable.** The only writer
     * is the control channel's boot hook, whose source is not compiled into a build made without
     * `-Psnapsync.rig=true` — so in a production binary nothing can assign this and it stays the inert
     * default forever. The mechanism a shipped process runs is a function of the device it runs on.
     *
     * It is a **thunk, replaced once at boot**, not a value: the arm re-resolves on every transition and
     * reads through it each time, so the channel can change the pin live without touching this field
     * again, and without the graph being rebuilt. It is deliberately assignable *before* `app` is forced —
     * the hook must not force the graph on a cold background wake (`ios-app-shell`).
     *
     * This replaced a design where production read a planted file from the App Group. That version could
     * be handed an override it never established — the container survives an application update, measured
     * on device — and needed a process-scoping rule to refuse one. Here the hazard cannot arise: the code
     * that writes this does not exist in the binary that must not honour it.
     */
    internal var uploadMechanismOverrideSource: () -> UploadMechanism? = { null }

    /**
     * The OS-driven mechanism, composed **only where its API exists**.
     *
     * The one remaining switch, and it earns its place: `setUploadJobExtensionEnabled` does not exist
     * below iOS 26.1, so constructing this object there would put a trapping selector one call away. Below
     * 26.1 the thunk never touches it, which is what keeps that structural rather than guarded. It decides
     * nothing else — it does not say which mechanism runs, only which one this OS *has*.
     */
    private val osDrivenUploadThunk: () -> UploadMechanismRuntime? =
        if (osSupportsOsDrivenUpload) ({ photoKitProducer }) else ({ null })

    private val shell: Shell = LiveShell()

    /** BGTaskScheduler identifier for the download import-tail backstop — MUST match the Swift host's
     * `register(forTaskWithIdentifier:)` and the Info.plist `BGTaskSchedulerPermittedIdentifiers`. */
    const val DOWNLOAD_BACKSTOP_TASK_ID: String = "app.snapsync.download.backstop"

    // The event config seam/store (one file-backed adapter is both — the App-Group file of record,
    // migration step 11a; the Keychain write-through ended at the finale), hoisted so a
    // (re)provision can read the current event id and the leave use-case can clear it.
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
     * Protected-data availability (capability `ios-app-shell`), read **directly** for the background
     * entry points' diagnostics: the Keychain and the app/App-Group containers are unreadable before
     * the first unlock since boot, and a background wake can land in exactly that window — this line
     * in `debug.log` is the only way to see it after the fact. The defer-and-resume gate that used
     * to sit here (`ProtectedDataGate`, `:domain:keychain`) is deleted (migration step 12, settled
     * proof ④: zero deferrals across all production logs — dead code): the adapters distinguish
     * unreadable from absent at every protected read, so a pre-first-unlock wake fails cleanly
     * (nothing mints, clears, or leaves) and converges at the next trigger, whose flow re-reads the
     * membership first (`AppPorts.reloadConfig`).
     */
    private suspend fun protectedDataAvailable(): Boolean =
        // `UIApplication` is main-thread-only, and this scope no longer runs there (law "Dispatcher
        // lanes are fixed by the composition") — so this read names the main lane explicitly instead
        // of inheriting it. It is a property read, not work: nothing blocking may follow it onto main.
        withContext(Dispatchers.Main) { protectedDataAvailableOnMain() }

    /**
     * The same read for entry points the OS already delivers **on the main thread**
     * (`handleBackgroundUrlSession`, invoked by the app delegate), where hopping would be a
     * round-trip to the thread we are on. Its name states the precondition, so the two forms cannot
     * be confused: everything reached from the composition lane takes the suspending one above.
     */
    private fun protectedDataAvailableOnMain(): Boolean =
        UIApplication.sharedApplication.isProtectedDataAvailable()

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
    private suspend fun albumExcludedAssetIds(cutoff: CaptureCutoff): Set<String> =
        runCatching { albumManager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff.at.iso) }
            .onFailure { log.w(it) { "denylisted-album lookup failed — admitting on doubt this cycle" } }
            .getOrDefault(emptySet()) // admit-on-doubt: a failed lookup must never DROP a real photo

    // The photo-library permission adapter, hoisted so the grant collector and a (re)provision share one
    // instance (both enable the extension; a provision must re-enable a producer a prior leave disabled).
    // `internal`, not `private`, for the same single reason as [app] and [host]: the rig's contributed hook
    // needs the photo-access port to drive the gallery wipe, which must ask for access before fetching or
    // its empty result is indistinguishable from an empty library. Module-wide, not exported to the ObjC
    // header, and absent from any build without `-Psnapsync.rig=true`.
    internal val permission: PhotoLibraryPermission by lazy { PhotoLibraryPermission() }

    // The stable per-install device id (the shared Keychain access group, addressed by name — the SAME
    // item the extension reads): the `/files/devices/<deviceId>/` partition the app's status lists.
    //
    // MINTING is the app's role alone (capability `device-identity`). It also owns adoption: if the
    // shared group is empty but an id exists in a group an older build wrote to, that value is taken
    // over verbatim rather than re-minted — a second identity would orphan this device's byte
    // partition and make its own uploads read as another member's.
    // `by lazy` and NOT memoizing a failure is load-bearing here: Kotlin's SynchronizedLazyImpl assigns
    // its value only on success, so a resolve that throws is retried on the next access rather than fixed
    // for the process. On a LOCKED device the store raises `SecureStoreUnavailable` rather than serving an
    // id, and without the retry one early touch would poison the identity for the life of the process —
    // silently, since nothing would re-attempt. `DeviceIdentityRetryTest` pins it rather than inheriting it.
    //
    // The store itself is chosen by COMPILATION TARGET (`deviceIdPrimaryStore`, capability
    // `device-identity`): the addressed Keychain on `iosArm64`, an App-Group file on `iosSimulatorArm64`
    // where that group cannot exist. Nothing here decides which — that is the point.
    private val deviceId: String by lazy {
        KeychainDeviceIdentity(DeviceIdentityRole.MINTING).deviceId()
    }

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
    // `internal`, not `private`, solely so the rig's contributed hook — compiled INTO this module under
    // `-Psnapsync.rig=true` — can pass it as a thunk without anything being widened to `public`.
    // `internal` is module-wide and is NOT exported to the `SnapSyncKit` ObjC header, so no framework
    // surface changes and no production build can reach it from outside this module.
    internal val app: AppCore by lazy {
        snapSyncApp(
            scope = scope,
            ports = AppPorts(
                // The main lane (law "Dispatcher lanes are fixed by the composition"). This shell is
                // the only place in the app process that may name it: platform UI runs here, and
                // nothing else does.
                uiLane = Dispatchers.Main,
                diagnosticsReporter = SentryDiagnosticsReporter(),
                // The diagnostic dump's two device-side inputs (capability `diagnostic-logging`):
                // the two log files (this process's own, and the extension's in the App Group) and
                // the build/OS/device facts. Both are adapter-resolved; the shell only names them.
                deviceLogSource = IosDeviceLogSource(),
                // The tier this OS is on — the same OS fact this field has always carried, so a dump
                // reads as it always did. Which mechanism is RUNNING is a newer, runtime-varying thing
                // that a value computed once could not tell the truth about; `foregroundParams` reports
                // that per trigger instead. Written as the resolver under a nominal full grant rather
                // than a branch, so this module keeps deciding nothing.
                diagnosticEnvironment = deviceDiagnosticEnvironment(
                    resolveUploadMechanism(osSupportsOsDrivenUpload, PermissionStatus.GRANTED).diagnosticName,
                ),
                configSource = config,
                configStore = config,
                photoAccess = permission,
                // The same adapter serves the status source and the request/Settings/picker surface —
                // the bundle's requestAccess/openSettings/choosePhotos commands bind to it in
                // `compose/`. The limited-library picker (capability `limited-photo-access`) is a
                // member of that port now, not a separate lambda this shell had to remember to pass.
                photoAccessRequester = permission,
                // The platform half of the share command: a system sheet over the top view controller
                // (:adapter:ios:app-only).
                sharePresenter = IosShareSheet(),
                candidateSource = candidateSource,
                // A cutoff-lowering reconfigure invalidates the shared discovery cursor so both tiers
                // re-enumerate and back-share the newly-in-scope older photos (capability
                // `reconfigure-membership`).
                clearDiscoveryCursor = discoveryStore::clearToken,
                // Selection snapshots under a partial grant (capability `limited-photo-access`):
                // observes only while LIMITED; each emission is one in-flow read serving N and the
                // cycle's discovery alike.
                selectionChanges = selectionSource,
                ledger = ledgerStore,
                downloadStore = downloadStore,
                // Full-access presence for the import guard; composition wraps it so a partial or
                // revoked grant never reports an asset as absent (capability `photo-download`).
                assetPresence = PhotoKitAssetPresence(),
                // Names the App-Group staging directory and frees the files of settled rows
                // (capability `download-store`) — one port owns both halves.
                stagedBytes = IosStagedBytes(),
                // The importer writes createdLocalId synchronously from inside a PhotoKit change
                // block (concrete store, not the port) and borrows the atomic album-add lookup.
                importer = IosPhotoLibraryImporter(
                    recordCreatedLocalId = { ref, id -> downloadStore.recordCreatedLocalId(ref, id) },
                    // The mirror, for a commit the library reports as failed (capability `download-store`).
                    // Guarded on the marker in the store's own write, so a report that arrives after the
                    // row moved on clears nothing.
                    clearCreatedLocalId = { ref, id -> downloadStore.clearCreatedLocalId(ref, id) },
                    // The success mirror: the completion settles the row itself, so an import whose
                    // requester is gone records its own outcome (capability `download-store`).
                    confirmCreatedLocalId = { ref, id -> downloadStore.confirmCreatedLocalId(ref, id) },
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
                newDownloadTransport = { host -> IosDownloadTransport(host) },
                union = HttpEventUnionSource(http, backendHost),
                directory = detailsSource,
                enrollment = HttpEnrollment(http, backendHost),
                // The App-Group file, so the record this app process invalidates at enroll is the same one
                // the ≥26.1 tier's producer reads in the EXTENSION process. A per-process record would
                // leave the extension believing the server still holds a projection the app just replaced.
                manifestStore = IosDeviceManifestStore(),
                eventCreation = HttpEventCreation(http, backendHost),
                eventRename = HttpEventRename(http, backendHost),
                attestKey = IosAttestKey(),
                attestClient = HttpAttestClient(darwinHttpClient(), backendHost),
                attestStore = KeychainAttestStore(),
                deviceId = { deviceId },
                clock = SystemClock,
                // The mechanisms this OS carries. WHICH one runs is resolution's answer, re-evaluated on
                // every transition (capability `upload-lifecycle`) — this root supplies only facts.
                appDrivenUpload = { urlSessionUpload },
                osDrivenUpload = osDrivenUploadThunk,
                osSupportsOsDrivenUpload = osSupportsOsDrivenUpload,
                // Deregistration ONLY — deliberately narrower than the OS-driven mechanism's `stop()`,
                // whose ledger clear and cursor reset would wipe rows the incoming mechanism reconciles
                // precisely (`upload-lifecycle`, `RelinquishThenRun`).
                relinquishOsRegistration = { photoKitProducer.deregister() },
                uploadMechanismOverride = uploadMechanismOverrideSource,
                albumManager = albumManager,
                albumMapStore = albumMapStore,
                albumExcludedAssetIds = { cutoff -> albumExcludedAssetIds(cutoff) },
                leaveNotifier = leaveNotifier,
                // Coordination is the `flow/` zone's (step 8); this root supplies the provision flow's
                // entry (a thin log-wrapped delegator) and the shell/platform effect lambdas the flows
                // coordinate over — each a port/platform touch a flow may not make directly.
                provision = ::provisionEvent,
                onEventMinted = { eventId -> host.onEventCreated(eventId) },
                refreshAttestation = ::refreshAttestation,
                // The trigger-time membership re-read (migration step 12): every flow re-reads the
                // persisted config before acting — cross-process writes and a pre-first-unlock seed
                // never notify this process's StateFlow, and the reload retains the last good value
                // on an unreadable read (the pure `configAfterReload` rule).
                reloadConfig = { config.reload() },
                scheduleBackstop = ::scheduleDownloadBackstop,
                // Re-register the APNs token on join (capability `push-registration`): re-`PUT`s the
                // current OS-delivered token so a device whose config the nightly sweep collected
                // (capability `scheduled-cleanup`) is pushable again the instant it rejoins warm. The
                // same idempotent `register` the launch/rotation collector uses; a null token (none
                // delivered yet) is a no-op.
                registerPush = {
                    pushTokenSource.token.value?.let { token ->
                        pushRegistration.register(ApnsPushToken(token, pushTokenSource.env))
                    }
                },
                // The upload arm's push receiver on the app-driven tier (a thunk — the tier controller
                // depends on this graph, so it must resolve lazily); null on iOS ≥26.1.
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
                // Deliberately detached, unlike every wake path: this fires from inside this client's
                // own response interceptor, so awaiting a refresh here would re-enter the interceptor
                // from within itself. It carries no OS receipt, so nothing is being falsely reported.
                scope.launch { refreshAttestation() }
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
    private suspend fun refreshAttestation() {
        // Awaited, not launched (law "A trigger flow never outlives its own run"). The launch also made
        // every trigger race its own credential: `refreshAttestation()` was fired alongside the fetches
        // it exists to authorize, so a request could go out carrying the token being replaced.
        // `refresh()` short-circuits on a fresh token, so awaiting costs nothing in the common case.
        //
        // Wiring, and nothing else. Both rules live in the trust feature: whether to surface at all
        // (only when the token is UNUSABLE and could not be replaced) and how long a verdict may be
        // shown (never past the start of the next refresh). The shell used to hold the cell those
        // rules wrote into, which is how a background wake's verdict survived a 26-hour suspension
        // onto a member's first frame (`SNAPSYNC-20`).
        app.attestation.refresh()
    }

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

    // The `LeaveNotifier` port that tells the backend this device is leaving (DELETE
    // /events/<id>/devices/<id>, capability `event-leave-endpoint`) — the backend renames the manifest
    // to its departed `.left.json` sibling and reaps/GCs the event when the last member leaves.
    // Best-effort (a failed call never blocks leaving). Used by BOTH the explicit Leave and a switch,
    // through the effect `compose/` builds from it.
    //
    // The device id goes in as a THUNK, not a value: resolving it reads the Keychain, and this adapter
    // is constructed while the graph is composed — which a locked background launch reaches before
    // first unlock. It is read per call, exactly as the composition's former closure over `deviceId`
    // did.
    private val leaveNotifier: HttpLeaveNotifier by lazy { HttpLeaveNotifier(http, backendHost) { deviceId } }

    // The device-facing backend host (baked at compile time); shared by every generic HTTP adapter
    // handed to the composed graph and the event-metadata (name) fetch. Reads through
    // `:adapter:ios:ext-safe`'s [bakedUploadBase] — the same call the boot diagnostic makes, so a
    // banner that disagreed with the host the adapters use is impossible, and the absent-key
    // defaulting decision stays out of this wiring-only shell.
    private val backendHost: String by lazy { bakedUploadBase() }

    // The ONE GET /events/:id client (capability `join-event`): the join gate's details fetch and the
    // best-effort scan-path/foreground name refresh both read through it — the latter via the
    // `EventDirectory` port in [AppPorts] (`directory`), whose fetch effect `compose/` builds for the
    // Foreground/Provision flows.
    private val detailsSource: HttpEventDirectory by lazy {
        HttpEventDirectory(http, backendHost)
    }

    // --- Push notifications (capability `push-registration`) ---
    // The compile-time APNs environment (the generated Deployment.plist's `apnsEnv`): `sandbox` for
    // dev/sideloaded builds, `production` for TestFlight/App Store. The token itself is OS-delivered
    // (the Swift AppDelegate forwards it via [onPushToken]); a rotation re-registers. Read through the
    // adapter, not inline: what an absent key becomes is a decision, and this shell holds none.
    private val pushTokenSource: PushTokenSource by lazy { PushTokenSource(bakedApnsEnv()) }

    // Registers the device APNs token with the backend (PUT devices/<id>/config) over the shared Darwin
    // client — on launch delivery and each rotation. Best-effort: a failed write is absorbed and retried
    // on the next token, never blocking join/upload/download. The collector is launched from [host].
    private val pushRegistration: PushRegistration by lazy {
        PushRegistration(KtorPushHttpClient(http), backendHost, deviceId = { deviceId })
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
            renameStatusSource = app.renameStatus,
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
            // The container's error seam (capability `sync-status-screen`): a throwable escaping a user
            // command lands here instead of propagating. `Error` severity deliberately — that is the
            // threshold at which a Kermit line becomes a crash-reporting EVENT rather than a breadcrumb
            // (capability `crash-reporting`), and a command that failed outright is exactly what should
            // reach the operator. It also keeps the line in `debug.log`, the un-redacted channel.
            onIntentError = { throwable -> log.e(throwable) { "user command failed" } },
            downloadSource = app.downloadStatusSource,
            attested = app.attestation.attested,
        )
    }

    /**
     * The host [MainViewController] renders. Built **once per process** (`by lazy`). There is only the
     * live host: a marketing screenshot is rendered by a separate binary that does not link this module
     * at all.
     */
    val renderHost: StatusContainerHost by lazy { shell.renderHost() }

    /** The join surface's shareable-count query (capability `join-share-count`) — the live query. */
    val shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int? get() = shell.shareableCount

    /** The photo grant, the count's recompute trigger. */
    val photoPermission: StateFlow<PermissionStatus> get() = shell.photoPermission

    /**
     * Wrap a platform entry point that lives outside this object — today only
     * [app.snapsync.ios.MainViewController], the Compose door Swift's `ContentView` calls. The
     * logger is private (one tag per process), so the wrap is offered rather than the logger
     * exposed; it decides nothing and adds no branch.
     */
    internal fun <T> platformEntry(name: String, params: String = "", block: () -> T): T =
        log.invocation(name, params = params) { block() }

    /**
     * Whether this **process** has ever been active — the input `UIApplication` cannot supply, because it
     * reports the current state only, and "has been active at least once" is precisely what separates a
     * background-woken process from an ordinary backgrounded one (capability `ios-app-shell`). Written by
     * [onForeground], which is the `didBecomeActive` observer; never reset, because a scene once composed
     * is kept.
     */
    private var everActive: Boolean = false

    /**
     * Whether the shell composes a Compose scene right now (capability `ios-app-shell`), resolved by the
     * pure, tested [resolveScene] from two inputs this object transcribes and does not interpret: the
     * platform's current application state and [everActive].
     */
    internal fun sceneMode(): SceneMode = resolveScene(
        appVisibilityFrom(UIApplication.sharedApplication.applicationState.value),
        everActive,
    )

    /**
     * The app became active — record it, and answer with the **scene generation** the SwiftUI shell binds
     * to `.id(…)` (capability `ios-app-shell`).
     *
     * The generation is `0` before any activation and `1` afterwards: it changes **exactly once per
     * process**, so the Compose view is built once — at the moment the deferred placeholder must become
     * the live scene — and never rebuilt again, however many times the app is foregrounded. Rebuilding on
     * every foreground would discard screen-local Compose state (an open settings surface, a half-typed
     * report, a scroll position) on every ordinary app switch, which is the option this change
     * deliberately did not take.
     *
     * It is a **value the shell binds**, not a command it obeys: SwiftUI needs something whose change it
     * can observe, and returning it here keeps the "when does the scene exist" rule in tested Kotlin
     * rather than in a Swift conditional.
     *
     * Distinct from [onForeground], which drives the foreground flow. This one only records.
     */
    @PlatformEntry
    fun onSceneActive(): Int = log.invocation("onSceneActive") {
        everActive = true
        SCENE_GENERATION_ACTIVE
    }

    /**
     * The app became active (observed from Kotlin via `UIApplicationDidBecomeActive` — see
     * [onLaunch]): refresh the status sources and start the foreground-gated ledger-counts poll so
     * upload status moves live while the screen is shown (capability `sync-status`). Like every OS
     * entry point here it is a thin pass-through to [shell], deciding nothing.
     */
    @PlatformEntry
    fun onForeground() = log.invocation("onForeground", params = foregroundParams()) {
        everActive = true
        shell.onForeground()
    }

    /**
     * The app is leaving the active state (`UIApplicationWillResignActive` — see [onLaunch]): stop
     * the status poll (a suspended app cannot act on fresher counts) and queue the download
     * import-tail backstop so any staged-but-unimported foreign assets get imported at the next
     * idle/charging window even if no further download wakes the app (capability `photo-download`, 5.4).
     */
    @PlatformEntry
    fun onBackground() = log.invocation("onBackground") { shell.onBackground() }

    /**
     * Install the UIKit lifecycle observers and realize this object — called by the Swift
     * `AppDelegate` from `didFinishLaunchingWithOptions` (a plain statement, no decision). The
     * foreground/background transitions are observed from **Kotlin** via `NSNotificationCenter`
     * (migration step 12: the SwiftUI `scenePhase` split was a Swift `if`, a decision the
     * transcriber law forbids): `didBecomeActive` ↔ the scene reaching `.active`,
     * `willResignActive` ↔ leaving it (including the transient `.inactive` cases — app switcher,
     * incoming call — which the old split also routed to background). Process-lifetime observers,
     * never removed; a background launch installs them too and simply never sees `didBecomeActive`.
     */
    @PlatformEntry
    fun onLaunch() = log.invocation("onLaunch") {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { onForeground() },
        )
        center.addObserverForName(
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { onBackground() },
        )
    }

    /**
     * A restored/continued `NSUserActivity` arrived (both halves of Universal-Link delivery —
     * forwarded **whole** from the Swift scene delegate, which decides nothing; capability
     * `event-link`). The tested `model/` filter-and-dispatch keeps only a browsing-web activity
     * with a URL and forwards the **complete** `absoluteString` — the fragment carries the whole
     * payload; this wiring transcribes the activity's fields and branches on nothing.
     */
    @PlatformEntry
    fun onLaunchActivity(activity: NSUserActivity) = deliverUserActivity("onLaunchActivity", activity)

    /**
     * WARM, via the scene delegate's `scene(_:continue:)` — the app's ONLY warm path.
     *
     * Measured working on iOS 26.5.2 twice: the 2026-07-16 session, and again on device
     * 2026-08-04 (8 warm deliveries, 8 hits).
     *
     * **Measured NOT working on iOS 18.7.9** (Bugsink `SNAPSYNC-25` + `SNAPSYNC-26`, iPhone XS,
     * build 607, one 80-second window). Three warm taps at 19:04:40, 19:05:12 and 19:05:31 each
     * brought the app to the front — `onForeground` fired every time, so iOS DID activate the app
     * from the link — and produced no line here at all; the third was while UNJOINED, which rules
     * out join and switch logic entirely. The cold entry then fired first try at 19:05:47 on a
     * fresh process, proving this delegate is the scene's delegate on 18 and is not inert. So on
     * 18.7.9 the platform activates the app for a universal link and does not call this hook.
     * Expiry: re-measure at the next iOS 18 point release; the evidence is one device.
     *
     * Its name is distinct from the cold entry's for exactly that reason — the dump pair above is
     * readable only because cold and warm could not be confused for one another.
     */
    @PlatformEntry
    fun onSceneContinueActivity(activity: NSUserActivity) =
        deliverUserActivity("onSceneContinueActivity", activity)

    /**
     * The scene connected, carrying [activities] restored/continued `NSUserActivity` values —
     * recorded **unconditionally, zero included** (spec `module-architecture`, "Absence is never
     * silent"; spec `diagnostic-logging`).
     *
     * The Swift cold hook's only Kotlin call used to sit *inside* its `forEach` over
     * `connectionOptions.userActivities`, so a scene connecting with an empty array recorded
     * nothing whatsoever — and `SwiftShellGuardTest`'s forwarding rule cannot see that, because the
     * call is lexically present and merely never runs. The cost is measured: on `SNAPSYNC-25` a
     * delegate that was installed and handed nothing was indistinguishable from a delegate that was
     * never installed, and that ambiguity was the whole investigation.
     *
     * It records and does nothing else. Doing no work can be right here; recording nothing never is.
     */
    @PlatformEntry
    fun onSceneWillConnect(activities: Int) =
        log.invocation("onSceneWillConnect", params = "activities=$activities") { }

    /**
     * UIKit is about to continue an activity of type [activityType] — offered **before**
     * `scene(_:continue:)`, and carrying only the type, never the activity.
     *
     * Observation only: it can deliver no URL, so it cannot fix the iOS 18 warm gap. It NARROWS it,
     * which is why it exists. Present with no [onSceneContinueActivity] after it ⇒ UIKit started a
     * continuation our delegate did not receive. Absent ⇒ UIKit never started one. Those two have
     * different fixes and the dumps so far cannot tell them apart.
     */
    @PlatformEntry
    fun onSceneWillContinueActivity(activityType: String) =
        log.invocation("onSceneWillContinueActivity", params = "type=$activityType") { }

    /**
     * The scene is entering the foreground, from its own delegate.
     *
     * Distinct from [onForeground], which Kotlin drives off the application-wide
     * `UIApplicationDidBecomeActive` notification: that one fires whether or not our scene delegate
     * is live, so it cannot answer "is the delegate being talked to?". This one can, and that is the
     * question a failed warm delivery leaves open.
     */
    @PlatformEntry
    fun onSceneWillEnterForeground() = log.invocation("onSceneWillEnterForeground") { }

    /** The scene became active, from its own delegate — see [onSceneWillEnterForeground]. */
    @PlatformEntry
    fun onSceneDidBecomeActive() = log.invocation("onSceneDidBecomeActive") { }

    /**
     * The scene was disconnected. Recorded because it changes which half of delivery a later link
     * takes: a disconnected scene makes the next link COLD (`onLaunchActivity`) even though the
     * process never died, and without this line that transition is invisible.
     */
    @PlatformEntry
    fun onSceneDidDisconnect() = log.invocation("onSceneDidDisconnect") { }

    /**
     * URL contexts were opened on the scene — the **custom-scheme** delivery path (capability
     * `event-link`).
     *
     * The `snapsync` scheme is retired and the Info.plist declares no `CFBundleURLTypes`, so this
     * should never fire. Forwarding it anyway is the point: if iOS 18 turns out to route a universal
     * link here, the log says so rather than the URL vanishing. It records only — it does NOT route
     * to `onOpenUrl`, because re-opening a second URL form is a behaviour change, not a diagnostic.
     */
    @PlatformEntry
    fun onSceneOpenUrlContexts(urls: List<String>) =
        log.invocation("onSceneOpenUrlContexts", params = "urls=${urls.size}") { }

    /**
     * The instrumented delivery of one `NSUserActivity`, shared by every hook that can receive one
     * (spec `diagnostic-logging`; spec `module-architecture`, "Absence is never silent").
     *
     * [hook] names the hook the platform actually invoked, so the device log distinguishes them —
     * that naming is the whole diagnostic value, not decoration. The enter line records the raw
     * fields **before** the filter tests them, and the exit line names the outcome even when nothing
     * is forwarded: on Bugsink `SNAPSYNC-3` the silent discard and a link iOS never delivered were
     * indistinguishable, and that ambiguity was the entire investigation.
     *
     * Straight-line by construction: the formatting and the filter-and-dispatch branch are the
     * tested `model/` codec's, so this wiring decides nothing (the shell gate counts even an elvis).
     */
    private fun deliverUserActivity(hook: String, activity: NSUserActivity): EventLinkDelivery {
        val activityType = activity.activityType
        val url = activity.webpageURL?.absoluteString
        return log.invocation(
            hook,
            params = userActivityParams(activityType, url),
            result = { outcome: EventLinkDelivery -> outcome.summary },
        ) {
            forwardEventLink(isWebLinkActivity(activityType), activityType, url, ::onOpenUrl)
        }
    }

    /**
     * The `BGProcessingTask` import-tail backstop (capability `photo-download`, 5.4): drains any
     * staged-but-not-yet-imported foreign assets when no further download event would wake the app
     * (e.g. the last transfer overran its URLSession wake budget). OS-scheduled (idle/charging) via the
     * Swift host's `BGTaskScheduler` registration; [onComplete] maps to `task.setTaskCompleted`.
     * Discovery stays foreground-only — this imports already-downloaded work, it does not re-read the union.
     */
    @PlatformEntry
    fun runDownloadBackstop(onComplete: () -> Unit) =
        log.invocation("runDownloadBackstop") { shell.runDownloadBackstop(onComplete) }

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
    @PlatformEntry
    fun handleBackgroundUrlSession(identifier: String, completionHandler: () -> Unit) =
        log.invocation("handleBackgroundUrlSession", params = "identifier=$identifier") {
            shell.handleBackgroundUrlSession(identifier, completionHandler)
        }

    /**
     * An event link arrived (forwarded raw from the Swift entry point — the complete URL, fragment
     * included, since the fragment carries the whole payload). Routed straight to
     * the container's **join gate** (capability `join-event`): it decodes, and either opens the
     * confirmation (a first join → full-screen; a different event while joined → switch dialog),
     * auto-confirms when the link carries `autoJoin=true` (the dev/headless trigger), or flashes the
     * invalid-link error. The app no longer provisions directly on scan — the gate owns that.
     */
    fun onOpenUrl(url: String) = log.invocation("onOpenUrl", params = "url=$url") { shell.onOpenUrl(url) }

    /**
     * The OS delivered an APNs device token (capability `push-registration`), forwarded raw-hex from the
     * Swift AppDelegate's `didRegisterForRemoteNotificationsWithDeviceToken`. Feed it to the token
     * source; the registration collector PUTs `devices/<id>/config`. Idempotent across launches and
     * rotations. Touch [host] so the collector is running to observe it. No decision in Swift.
     */
    @PlatformEntry
    fun onPushToken(hex: String) =
        log.invocation("onPushToken", params = "hex=${hex.take(12)}…") { shell.onPushToken(hex) }

    /**
     * APNs registration **failed** (capability `push-registration`), forwarded from the Swift
     * AppDelegate's `didFailToRegisterForRemoteNotificationsWithError` with the error already
     * rendered to a string (an encoding, not a decision).
     *
     * It reaches Kotlin because the Swift side used to `NSLog` it — and os_log redacts an
     * interpolated format string wholesale, so the line appeared **nowhere**: not in
     * `idevicesyslog`, not in `debug.log`. A device with no push token silently never receives a
     * silent push, and nothing said why. Warn, not error: registration failure is expected on a
     * build with no APNs entitlement and on a device with no network, and the app runs on without it.
     */
    @PlatformEntry
    fun onPushTokenFailure(description: String) =
        log.invocation("onPushTokenFailure", params = "error=$description") {
            log.w { "APNs registration failed — no silent pushes will arrive: $description" }
        }

    /**
     * A silent (`content-available`) remote notification arrived (capability `push-registration`),
     * its [userInfo] forwarded **whole** from the Swift AppDelegate (migration step 12 — the
     * `eventId` extraction was a Swift `guard`; the tested `model/` codec owns it now, inside the
     * SilentPush flow). The flow fans the push out to the arms' receivers, which — if it names the
     * active event — reconcile downloads (union read + enqueue). Touch [host] so the download stack
     * is assembled on a background launch. Non-throwing: a failure still calls [completion].
     */
    @PlatformEntry
    fun onSilentPush(userInfo: Map<Any?, *>, completion: () -> Unit) =
        log.invocation("onSilentPush") { shell.onSilentPush(userInfo, completion) }

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
        params = "eventId=${cfg.eventId} name=${cfg.name} cutoff=${cfg.minPhotoDate}",
    ) {
        // The switch-leave → save → refresh → arm → album → reconcile coordination is the
        // `flow/Provision` trigger's; this thin wrapper keeps only the entry-point log context (over
        // IosLogScope) so the flow's synchronous steps carry `[provisionEvent]` and its escaping launches
        // (reconcile / push) self-label, exactly as before. Nothing here destructures the config (a
        // newly-added field must not be dropped before the save the extension reads).
        app.provisionFlow.run(cfg)
    }

    // The permission-grant subscriptions (upload-arm start + event-album ensure) live in `compose/`
    // (`AppCore.installPermissionSubscriptions`, migration step 8) and are installed ONLY from the
    // [host] assembly above — never on mere [AppCore] construction, so a cold background wake starts
    // no producer off the StateFlow's replay. Both fire only on a *transition* to GRANTED, so neither
    // can rescue a membership provisioned while access was already granted — the provision flow owns
    // that case.

    // The two candidate mechanisms this OS can carry. Both are `by lazy`: `PhotoKitUploadProducer` is
    // constructed only where its registration selector exists (≥26.1), so no code path can trap on a
    // lower system. Which one RUNS is `resolveUploadMechanism`'s answer, re-read at every transition —
    // on ≥26.1 under a partial grant BOTH are constructed and only the app-driven one is started.
    // (The tier-neutral `UploadArm` — which verb fires on which membership transition — is composed in
    // the app graph as `app.uploadArm`, over the resolved mechanism.)
    private val photoKitProducer: PhotoKitUploadProducer by lazy {
        PhotoKitUploadProducer(ledgerStore, log)
    }

    // The ONE PhotoKit read seam this process holds (shared by the gallery walk and the
    // selection-snapshot mapping — one mapping, one place).
    // One instance serves every reader — the status total, the join preview, both upload tiers, and the
    // selection observer. There used to be two (a resource-reading walk and a facts-only one) because the
    // seam forced the choice at construction; a candidate defers it to the caller instead, so the cheap
    // and the expensive read are the same source asked different questions.
    private val candidateSource: PhotoKitCandidateSource by lazy { PhotoKitCandidateSource() }

    // The shared App-Group discovery cursor (capability `reconfigure-membership`): any `IosDiscoveryStore`
    // instance reads/writes the same App-Group token, so this clears the cursor both upload tiers consult.
    private val discoveryStore: IosDiscoveryStore by lazy { IosDiscoveryStore() }

    // The selection-change source (capability `limited-photo-access`): registers the library observer
    // only while permission is LIMITED; the app graph collects its snapshots.
    private val selectionSource: PhotoSelectionSnapshotSource by lazy {
        PhotoSelectionSnapshotSource(permission.permission, scope, candidateSource)
    }

    // The app-driven mechanism's composition root. Built lazily; reached whenever resolution yields
    // URL_SESSION — every OS below 26.1, and ≥26.1 under a partial grant — plus the background-session
    // drain, which may adopt an old upload session whichever mechanism is live.
    private val urlSessionUpload: UrlSessionUploadController by lazy {
        UrlSessionUploadController(
            scope, ledgerStore, config,
            permission = { permission.permission.value },
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
            // In-process liveness: after each pump cycle, re-read the ledger counts so status moves live.
            onCycleComplete = { app.ledgerCounts.refresh() },
            // Event album (capability `event-album`): the composed coordinator; the cycle applies the
            // membership's opt-in (which arrived with its gate) and `uploadCore` owns the shared
            // `assetId` denormalization.
            albumCoordinator = app.albumCoordinator,
            // The walk-vs-snapshot decision, derived by the app graph from current permission + the
            // latest snapshot (capability `limited-photo-access`).
            selectionScope = { app.selectionScope() },
            // Where this session's OS completion handler is released — the main lane, because UIKit
            // owns that handler and requires it (capability `ios-app-shell`). Named here, in the one
            // app-process file the lane gate permits to name it.
            uiLane = Dispatchers.Main,
        )
    }

    /** The upload heartbeat BGProcessingTask handler (app-driven tier). Registered in the Swift shell. */
    @PlatformEntry
    fun runUploadHeartbeat(onComplete: () -> Unit) =
        log.invocation("runUploadHeartbeat") { shell.runUploadHeartbeat(onComplete) }

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

    /** The `onForeground` invocation params. `force=` is gone with the launch flag that set it; a rig
     *  build can still pin a mechanism, but it pins what these two values already report, so they say
     *  everything either way. */
    private fun foregroundParams(): String =
        "mechanism=" +
            resolveUploadMechanism(osSupportsOsDrivenUpload, permission.permission.value).diagnosticName +
            " osSupported=$osSupportsOsDrivenUpload"

    // ── The shell delegate every OS entry point passes through ──────────────────────────────────

    /**
     * What every OS entry point delegates to.
     *
     * **One implementation** ([LiveShell]). It was once implemented per composition mode; the forge is a
     * separate binary now and no switch chooses between them. What it still buys is enumerating the OS
     * entry-point surface in one place, which is why it is kept — see the decision record
     * `correct-superseded-composition-claims` (D2).
     */
    private interface Shell {
        fun renderHost(): StatusContainerHost

        /** The join-time shareable-count query (capability `join-share-count`). */
        val shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int?

        /** The photo grant, the count's recompute trigger. */
        val photoPermission: StateFlow<PermissionStatus>
        fun onForeground()
        fun onBackground()
        fun onOpenUrl(url: String)
        fun onPushToken(hex: String)
        fun onSilentPush(userInfo: Map<Any?, *>, completion: () -> Unit)
        fun runUploadHeartbeat(onComplete: () -> Unit)
        fun runDownloadBackstop(onComplete: () -> Unit)
        fun handleBackgroundUrlSession(identifier: String, completionHandler: () -> Unit)
    }

    /**
     * The live composition: the real stack.
     *
     * It takes **no tier thunks any more**. It used to take six, of which two were already identical
     * between the tiers and the rest said "how is this mechanism kicked" — a question belonging to the
     * mechanism, not to a table in a wiring-only module. Every OS entry point below now delegates to the
     * *resolved* mechanism, which answers or declines for its own stated reason, so adding a third
     * mechanism is a new producer rather than a new branch here.
     */
    private class LiveShell : Shell {
        /** Touching [host] assembles the live stack (and installs the grant subscriptions). */
        override fun renderHost(): StatusContainerHost = host

        // The real permission-aware, no-network count query and the live grant (capability `join-share-count`).
        override val shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int? get() = app::loadShareableCount
        override val photoPermission: StateFlow<PermissionStatus> get() = app.photoPermission


        override fun onForeground() {
            // `scope.launch` because the flow is `suspend` now (law "A trigger flow never outlives its
            // own run"). The entry line is the PUBLIC wrapper's — logging again here would emit two
            // `→ onForeground` lines — so this reports the dispatch, not the flow. Acceptable only
            // because this entry point carries no OS completion handler: nothing is falsely reported
            // to the system, unlike the receipt paths.
            scope.launch {
                host
                // The whole foreground coordination — membership re-read, attestation, pump, the
                // foreground-gated status poll's start (the ding's replacement, spec `sync-status`),
                // refresh / reconcile / name — is the flow's, and it is awaited.
                app.foregroundFlow.run()
            }
        }

        override fun onBackground() {
            scope.launch {
                // Stop the status poll + arm the backstop — the `flow/Background` trigger's coordination.
                app.backgroundFlow.run()
                log.i { "=== app entering background ===" }
            }
        }

        // Braces, not `=`: the container's intent returns a Job and the seam is Unit.
        override fun onOpenUrl(url: String) {
            host.onOpenUrl(url)
        }

        override fun onPushToken(hex: String) {
            // Touch [host] so the registration collector is running to observe it. No decision in Swift.
            host
            pushTokenSource.deliver(hex)
        }

        override fun onSilentPush(userInfo: Map<Any?, *>, completion: () -> Unit) {
            host
            scope.launch {
                // Wrap INSIDE the launch so `[onSilentPush]` spans the async reconcile (and the download
                // HTTP + import lines it drives trace back to this push). The payload decode →
                // membership re-read → attestation → cross-arm fan-out coordination is the
                // `flow/SilentPush` trigger's (it absorbed FanOutPushReceiver); only the entry-point
                // wrap and the OS completion handler stay shell-local.
                // The OS handler is released by the receipt, after the fan-out or on its deadline —
                // never before (capability `ios-app-shell`). The former `finally { completion() }` was
                // structurally sound and still wrong: the flow it wrapped detached its own work, so the
                // handler went out against a fan-out that had not started.
                OsReceipt(
                    entryPoint = "onSilentPush",
                    deadline = ReceiptDeadlines.SILENT_PUSH,
                    release = completion,
                ).heldFor {
                    log.invocation(
                        "onSilentPush.run",
                        params = "protectedData=${protectedDataAvailable()}",
                    ) {
                        app.silentPushFlow.run(userInfo)
                    }
                }
            }
        }

        /**
         * The `BGProcessingTask` heartbeat. The **entry point** holds the OS handler, for the deadline
         * named for this wake, and the mechanism receives a plain `suspend` trigger — so no mechanism can
         * fail to release a handler, because none ever holds one. Moved here from inside the app-driven
         * controller: same scope, same deadline constant, same `heldFor`; only the construction site
         * changed, and now a mechanism that declines still answers the OS.
         */
        override fun runUploadHeartbeat(onComplete: () -> Unit) {
            scope.launch {
                OsReceipt(
                    entryPoint = "runUploadHeartbeat",
                    deadline = ReceiptDeadlines.BACKGROUND_TASK,
                    release = onComplete,
                ).heldFor { app.uploadArm.triggers.onBackgroundTask() }
            }
        }

        override fun runDownloadBackstop(onComplete: () -> Unit) {
            scope.launch {
                // Wrap INSIDE the launch so `[runDownloadBackstop]` spans the async import. The
                // protected-data state rides the entry-point line (capability `ios-app-shell`): a
                // background wake on a locked device is otherwise invisible, and it is the only place
                // this class of bug shows up — no test can reach it. The membership re-read /
                // attestation / import coordination is the `flow/DownloadBackstop` trigger's; only
                // the entry-point wrap and re-arm stay shell-local.
                try {
                    OsReceipt(
                        entryPoint = "runDownloadBackstop",
                        deadline = ReceiptDeadlines.BACKGROUND_TASK,
                        release = onComplete,
                    ).heldFor {
                        log.invocation("runDownloadBackstop.run", params = "protectedData=${protectedDataAvailable()}") {
                            app.downloadBackstopFlow.run()
                        }
                    }
                } finally {
                    // Re-arm for the next idle window on EVERY path including a throw: a lost re-arm
                    // silently ends the backstop chain. The task assertion itself is the receipt's,
                    // released after the drain rather than after the dispatch.
                    scheduleDownloadBackstop()
                }
            }
        }

        // PINNED shell decision (spec `module-architecture`, "Shells are wiring only" — pinned
        // forms; inventory gated by KotlinShellGuardTest). Forcing proof: UIKit delivers ONE app
        // delegate callback — `application(_:handleEventsForBackgroundURLSession:completionHandler:)`
        // — for EVERY background URLSession identifier (API contract; there is no per-session
        // registration surface), and this app owns two OS-reattached sessions (the 18–26.0 upload
        // tier's and the download session). Mapping the OS-supplied identifier to its session owner
        // is transcription of the callback's own discriminator, inexpressible anywhere but where
        // both session objects live. Expiry: dies with the 18–26.0 app-driven tier (re-evaluate at
        // iOS 27 GM, ~Sept 2026, with the async extension protocol).
        @Suppress("CyclomaticComplexMethod")
        override fun handleBackgroundUrlSession(identifier: String, completionHandler: () -> Unit) = log.invocation(
            "handleBackgroundUrlSession.route",
            params = "protectedData=${protectedDataAvailableOnMain()}",
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
 * The **composition lane** (spec `module-architecture`, law "Dispatcher lanes are fixed by the
 * composition"): the one thread every live-core coroutine in this process runs on.
 *
 * **Why not the main thread.** Whether a port call blocks *the main thread* is a property of the
 * caller's dispatcher, not of the adapter — so it cannot be judged where the call is written, and it
 * was not: 21 of 23 iOS adapter files touching a blocking platform API hop nowhere. Owning the
 * decision here makes a blocking call off-main by construction. Forcing proof: build 521 died on an
 * iPhone11,2 / iOS 18.7.9 with `assetsd` wedged inside `fetchPersistentChangesSinceToken`, 0.071 s of
 * app CPU across the whole watchdog allowance — blocked, not busy (`IosDiscovery`).
 *
 * **Why exactly one thread.** `Dispatchers.Main` is single-threaded and core code relies on that for
 * mutual exclusion — `PhotoSelectionSnapshotSource`'s lock-free register/unregister and
 * `SentryDiagnosticsReporter`'s plain init flag both say so, and whatever else assumes it cannot be
 * enumerated. One thread changes which thread and nothing else; a pool would silently turn every
 * un-enumerated assumption into a race.
 *
 * **Why its own thread rather than a slice of [Dispatchers.Default].** Orbit's event loop reduces
 * presentation state on `Default`. A blocked platform call parked in that pool would stall the UI's
 * own updates — an OS kill traded for a frozen screen. `Dispatchers.IO` would be the obvious home and
 * is **`internal`** on Kotlin/Native (coroutines 1.10.2): it is in the klib but not callable, measured
 * by compile, not read off a symbol table. Expiry trigger: a coroutines release that publishes it.
 *
 * `@DelicateCoroutinesApi` flags contexts that are never closed. That is the requirement here, not the
 * hazard: this scope lives as long as the process, and closing its dispatcher is what must not happen.
 */
@OptIn(DelicateCoroutinesApi::class)
private val compositionLane = newFixedThreadPoolContext(nThreads = 1, name = "snapsync-composition")

/**
 * The scene generation once the app has been active (capability `ios-app-shell`). `0` is the
 * never-activated value the shell starts from; this is the only other value it ever takes, so SwiftUI's
 * `.id(…)` changes exactly once per process — rebuilding the Compose view at the first activation and
 * never again.
 */
private const val SCENE_GENERATION_ACTIVE: Int = 1
