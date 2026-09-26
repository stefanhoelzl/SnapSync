package app.snapsync.ios

import app.snapsync.compose.AppCore
import app.snapsync.compose.AppPorts
import app.snapsync.compose.PushPorts
import app.snapsync.compose.UploadRecordPorts
import app.snapsync.host.ComposedApp
import app.snapsync.host.snapSyncHost
import app.snapsync.files.IosFiles
import app.snapsync.ports.Files
import app.snapsync.services.config.ConfigService
import app.snapsync.config.bakedApnsEnv
import app.snapsync.config.bakedAppStoreUrl
import app.snapsync.attest.IosDeviceIntegrity
import app.snapsync.http.HttpBackend
import app.snapsync.logging.appMarketingVersion
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.ports.Backend
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.gallery.IosGallery
import app.snapsync.gallery.IosGalleryReader
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ios.registry.extensionRegistry as platformExtensionRegistry
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.scene.IosLifecycle
import app.snapsync.scene.IosUi
import app.snapsync.scene.SceneRecord
import app.snapsync.link.IosLinks
import app.snapsync.push.IosPushNotifications
import app.snapsync.push.IosPushRegistrationRecord
import app.snapsync.time.SystemClock
import app.snapsync.ports.PushTokenSource
import app.snapsync.metrics.MetricKitProcessMetrics
import app.snapsync.membership.darwinHttpClient
import app.snapsync.download.IosDownload
import app.snapsync.ios.urlsession.BackgroundSessions
import app.snapsync.ios.urlsession.IosUrlSessionUploadPlatform
import app.snapsync.ios.urlsession.UPLOAD_SESSION_ID
import app.snapsync.compose.AppUploaderPorts
import app.snapsync.compose.appUploader
import app.snapsync.ports.AlbumMapStore
import app.snapsync.preferences.IosPreferences
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.staging.StagingService
import app.snapsync.systemui.IosSystemUi
import app.snapsync.protection.IosProcessInfo
import app.snapsync.databases.IosDatabases
import app.snapsync.ports.Databases
import app.snapsync.services.downloads.DownloadService
import app.snapsync.background.IosBackgroundTime
import app.snapsync.background.IosWake
import app.snapsync.ports.DeviceIdentity
import app.snapsync.model.uploadersCarried
import app.snapsync.ports.LedgerStore
import app.snapsync.config.bakedUploadBase
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.preferences.removeOrphanedJoinMarker
import app.snapsync.model.PlatformEntry
import app.snapsync.logging.FileLogSink
import app.snapsync.logging.appLogDestination
import app.snapsync.services.logs.LogTailService
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.logging.SentryCrashReporter
import app.snapsync.config.bakedSentryDsn
import app.snapsync.compose.ProcessPorts
import app.snapsync.compose.ProcessServices
import app.snapsync.compose.snapSyncProcess
import app.snapsync.logging.appBuildVersion
import app.snapsync.logging.IosEntryContext
import app.snapsync.logging.PublicNSLogSink
import app.snapsync.logging.neverBlockOnStdio
import app.snapsync.identity.NoPlatformDeviceId
import app.snapsync.keychain.platformSecureStore
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.ports.SecureStore
import app.snapsync.services.identity.AttestState
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import app.snapsync.ios.qos.newUserInitiatedLane
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSUserActivity
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo

/**
 * The iOS composition root (D7): a single app-lifetime singleton that assembles the real live
 * stack. It owns a `SupervisorJob` scope on the main dispatcher so the source's collector and the
 * Orbit container outlive Compose recomposition (not a `rememberCoroutineScope`, which dies with
 * the view). The app has exactly one root screen, so process-lifetime ownership is correct; the
 * Swift entry point stays untouched. Move ownership to Swift only if scene-aware lifecycle or
 * scope recreation (multi-window, reset/logout) is ever needed.
 *
 * Assembly is lazy so it runs once on first view creation, and it goes through the SHARED
 * composition (`snapSyncApp`, `docs/architecture.md` "One shared composition"): this root
 * constructs the platform adapters and hands them as [AppPorts]; the feature graph — status
 * sources, attestation, join/leave/create, downloads, the upload arm — is composed in `:domain`'s
 * `compose/` zone as [app]. `permission` and `config` are each passed as both their ports (one
 * adapter implements both).
 *
 * **Upload lifecycle lives elsewhere.** This root constructs the mechanisms this OS can carry (the PhotoKit
 * extension registration on iOS ≥26.1, the in-app URLSession engine everywhere) and the composed, stateless
 * `UploadTransitions` (`app.uploadTransitions`) decide what each membership transition does to them. The
 * *decision* — which verb fires on join / reconfigure / grant / launch / leave — is not made here, because
 * this module is wiring-only and
 * untested by the project's hard rule, and parking that decision here is precisely how the app-driven tier
 * shipped a provision path that destroyed its ledger and started nothing (capability `background-upload`).
 *
 * **The OS entries are the adapters'.** Every callback the Swift shell forwards reaches an entry port's adapter in
 * one line — `Lifecycle`, `Links`, `PushNotifications`, `Ui` (`docs/architecture.md`, "Events arrive through
 * `listen`") — and what a delivery runs is the composition's handler, registered as the graph is composed. So which
 * own work an entry runs, how its completion is held and when the tail runs are written once, in `compose/`, and
 * pinned by the world's entry tests. What stays here is what only a root can do: build the adapters, the ONE cutoff
 * formatter, and the build's adapter set (`platformAdapters()`), and compose at launch (`onLaunch`).
 */
object SnapSyncRoot {

    init {
        // First, before anything logs: a DVT- or Xcode-launched process writes NSLog's stderr copy (and Ktor's stdout)
        // into a pipe the host drains, and an undrained one used to wedge every logging thread (see the KDoc).
        neverBlockOnStdio()
    }

    /**
     * The OS's own account of how this process has been behaving (capability `privacy-security`) — the MetricKit
     * seat, constructed HERE, in the object's own initialization, and listened to by [process] right below. Two
     * measured facts force the seat, and neither is a preference:
     *
     *  1. MetricKit accumulates **nothing** for an app until a process first touches it, and never
     *     retroactively — so the earliest path that runs on every launch is the only correct seat. [app] is
     *     `by lazy` precisely so a cold background wake does not force the graph, and a wake that never forced
     *     it would be a day of attribution nobody gets back.
     *  2. Delivery is **one-shot**: reports wait indefinitely while nothing listens, then are handed over exactly
     *     once. Listening without a live handler therefore DISCARDS a report the OS was holding safely — worse
     *     than not listening at all. `snapSyncProcess` builds the handler before it listens.
     *
     * Read by nobody but the composition on purpose: **the field IS the retention.** The adapter holds the OS
     * subscriber, and MetricKit is not documented to keep a strong reference to it — so a collected adapter would
     * take the subscriber with it, and this would fail silently, and only on the devices that had something to
     * report.
     */
    private val processMetrics: MetricKitProcessMetrics = MetricKitProcessMetrics()

    /**
     * This process's per-process services (`snapSyncProcess`, every root's first act): its log writers and boot
     * banner, its ONE crash reporter — started here, before any other wiring can fail — its process metrics, its
     * files, its clock and its entry-point seam.
     *
     * `internal`, not `private`, for one further reader: the rig's contributed hook drives a synthetic process-metric
     * report through [ProcessServices.processAccount], THIS instance — exercising a copy would prove only that the
     * copy works. `internal` is module-wide and is not exported to the `SnapSyncKit` ObjC header.
     */
    internal val process: ProcessServices = snapSyncProcess(
        ProcessPorts(
            crashReporter = SentryCrashReporter(),
            processMetrics = processMetrics,
            // A public NSLog sink AND a file sink. NSLog is redacted as `<private>` on current iOS (dynamic format
            // strings are private), so the file (Documents/debug.log, pulled via `pymobiledevice3 apps pull`) is the
            // reliable channel. The app's log stays in its OWN Documents — it can read it without help, so relocating
            // it would break every pull command and buy nothing (capability `privacy-security`).
            logSinks = listOf(PublicNSLogSink(), FileLogSink(appLogDestination().path)),
            files = IosFiles(),
            clock = SystemClock,
            entryContext = IosEntryContext,
            dsn = bakedSentryDsn(),
            bootLines = listOf(
                // Names the process + build version so a reader who concatenates the app/extension files can tell
                // runs apart (capability `privacy-security`, D5).
                "=== app process start build=${appBuildVersion()} ===",
                // The BAKED backend this build talks to. It names the one fact that makes an otherwise-silent
                // failure legible: point a build at a different backend without a device reset and the ledger
                // still says COMPLETED, so the device uploads nothing — no error, no failed request. Read beside
                // the cycle's own `enumeration: N seen, X new, Y already-uploaded`, a changed host beside an
                // unchanged ledger names the cause immediately.
                "[boot] upload base = ${bakedUploadBase()}",
            ),
            ownsGlobalLogger = true,
        ),
    )

    init {
        // The retired join marker's orphaned App-Group key goes on every start — it is what keeps a revert
        // of `join-loads-leave-clears` clean (see [removeOrphanedJoinMarker]). Idempotent, no bookkeeping. Its
        // own adapter instance: the properties below are not initialised yet in this block.
        removeOrphanedJoinMarker(IosPreferences())
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

    // ── The OS fact → upload-mechanism PRESENCE (`docs/architecture.md`, "One shared composition") ──

    // `internal` (not `private`) for the same single reason as [app]: the rig's contributed hook reports
    // this OS fact on `/device/state`, and reading the value the app actually resolved is the only
    // way to report it without a second resolution that could disagree. `internal` is module-wide and is
    // not exported to the `SnapSyncKit` ObjC header.
    /**
     * Whether this OS carries the OS-driven upload mechanism at all (iOS ≥26.1).
     *
     * This is the whole of what the shell still decides about uploads. It is **presence**, not behaviour:
     * whether the extension may be registered is derived from this fact plus the current permission plus the
     * rig's switch, by the pure `extensionRegistrable` in `:domain model/`, re-evaluated at every transition;
     * the app's uploader runs on every OS, beside the extension.
     */
    internal val osSupportsOsDrivenUpload: Boolean = backgroundUploadSupported()

    // This process's files, by area: the App-Group container and its own Documents. One instance; the file-backed
    // services below are built over it (`docs/architecture.md`).
    private val files: Files get() = process.files

    // The device manifest's skip record: one instance for the app graph's producer and the app's uploader.
    private val manifestStore: DeviceManifestService by lazy { DeviceManifestService(files) }

    // The event config (one service is all three config ports — the App-Group file of record), hoisted so a
    // (re)provision can read the current event id and the leave use-case can clear it.
    private val config: ConfigService by lazy { ConfigService(files) }

    /**
     * The one cutoff formatter every surface shares (capability `photo-sharing`) — the status host's
     * own, built by the shared host composition over the `Clock` port's system adapter.
     */

    // Event album (capability `event-album`): the shared leave-surviving `eventId → albumLocalId` map the composed
    // coordinator (`app.albumCoordinator`) sits on.
    private val albumMapStore: AlbumMapStore by lazy { AlbumMapService(IosPreferences(), secureStore) }


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
    // MINTING is the app's role alone (capability `photo-sharing`). It also owns adoption: if the
    // shared group is empty but an id exists in a group an older build wrote to, that value is taken
    // over verbatim rather than re-minted — a second identity would orphan this device's byte
    // partition and make its own uploads read as another member's.
    // The resolve is the identity service's (`PersistedDeviceIdentity`, `docs/architecture.md`): it keeps its
    // first success and never a failure, so a resolve that fails on a LOCKED device is retried on the next call
    // rather than fixed for the process.
    //
    // The store is chosen by COMPILATION TARGET (`platformSecureStore`, capability `photo-sharing`): the Keychain
    // on `iosArm64`, the device-id slot in an App-Group file on `iosSimulatorArm64` where the shared group cannot
    // exist. Nothing here decides which — that is the point.
    private val deviceIdentity: DeviceIdentity by lazy {
        PersistedDeviceIdentity(DeviceIdentityRole.MINTING, secureStore, NoPlatformDeviceId())
    }

    // This process's protected small-value store: the device id, the attestation token and key id, and the legacy
    // album map's last seat — one instance, every item addressed by its slot.
    private val secureStore: SecureStore by lazy { platformSecureStore() }

    /**
     * The composed app graph (`docs/architecture.md`, "One shared composition"): this root
     * constructs the platform adapters and coordination lambdas as [AppPorts]; `snapSyncApp`
     * composes the features. Every [AppCore] property is `by lazy`, so first-touch construction
     * timing matches the lazy web that used to live here — nothing resolves the device identity or
     * opens a protected store earlier than before (the locked-background-launch property).
     */
    // `internal`, not `private`, solely so the rig's contributed hook — compiled INTO this module under
    // `-Psnapsync.rig=true` — can pass it as a thunk without anything being widened to `public`.
    // `internal` is module-wide and is NOT exported to the `SnapSyncKit` ObjC header, so no framework
    // surface changes and no production build can reach it from outside this module.
    internal val app: AppCore get() = composed.core

    /**
     * The core AND the status host over it, from the shared host composition (`docs/architecture.md`, "One
     * shared composition"): this root supplies ports and nothing else. `by lazy`, and cheap to force — every
     * [AppCore] property is itself `by lazy`, and host assembly happens only when [host] is first touched.
     */
    private val composed: ComposedApp by lazy {
        snapSyncHost(
            scope = scope,
            process = process,
            cutoffFormatter = cutoffFormatter,
            ports = AppPorts(
                // The main lane (law "Dispatcher lanes are fixed by the composition"). This shell is
                // the only place in the app process that may name it: platform UI runs here, and
                // nothing else does.
                uiLane = Dispatchers.Main,
                // Recorded by the background entry points; decides nothing (capability `sync-status`).
                processInfo = IosProcessInfo(),
                // The diagnostic dump's two device-side inputs (capability `privacy-security`):
                // the two log files (this process's own, and the extension's in the App Group) and
                // the build/OS/device facts. Both are adapter-resolved; the shell only names them.
                deviceLogSource = LogTailService(files),
                // Which uploaders this OS carries — a constant of the build. What each may do right now is
                // runtime-varying, and the dump's state section reports it from the composition's own answers.
                diagnosticEnvironment = deviceDiagnosticEnvironment(uploadersCarried(osSupportsOsDrivenUpload)),
                configSource = config,
                configStore = config,
                photoAccess = permission,
                // The platform's own UI — the share sheet, the store link, the Settings page (:adapter:ios:app-only).
                systemUi = IosSystemUi(),
                // Every photo-library read and write, the partial grant's selection observer (opened at host
                // assembly only) and the import of foreign photos, whose markers the core's handlers write.
                gallery = gallery,
                // What this process knows about its own uploads: the ledger. The app loads it from the backend's
                // per-device listing at a join and clears it at a leave, on every tier (capability
                // `photo-sharing`) — on >=26.1 as a non-writer, through the reset family.
                uploadRecord = UploadRecordPorts(ledger = ledgerStore),
                downloadStore = downloadStore,
                // Names the App-Group staging directory and frees the files of settled rows
                // (capability `receiving-photos`) — one port owns both halves.
                stagedBytes = StagingService(files),
                // The platform's background downloads — an event port the host zone listens to.
                download = downloadAdapter,
                // The backend: every need-shaped service is composed over it inside the core.
                backend = backend,
                // The App-Group file, so the record this app process invalidates at enroll is the same one
                // the ≥26.1 tier's producer reads in the EXTENSION process. A per-process record would
                // leave the extension believing the server still holds a projection the app just replaced.
                manifestStore = manifestStore,
                integrity = IosDeviceIntegrity(),
                attestStore = AttestState(secureStore),
                deviceIdentity = deviceIdentity,
                // The update-required screen's one remedy (capability `app-update-required`).
                appStoreUrl = bakedAppStoreUrl(),
                // The mechanisms this OS carries. WHICH one runs is resolution's answer, re-evaluated on
                // every transition (capability `background-upload`) — this root supplies only facts.
                appDrivenUpload = {
                    appUploader(
                        app,
                        AppUploaderPorts(
                            // The THREE-state membership read, never the core's StateFlow (capability `join-event`).
                            config = config,
                            grant = PhotoGrantRead(::currentPhotoPermission),
                            host = backendHost,
                            appVersion = appMarketingVersion(),
                        ),
                    )
                },
                // The app's uploader transport: a background `URLSession` on every iOS version.
                appUpload = uploadAdapter,
                // The upload extension's registration record, on every OS: below iOS 26.1 the adapter answers
                // `Unsupported` itself (the version check is its own), so the root holds no `if` around it.
                extensionRegistry = extensionRegistry,
                osSupportsOsDrivenUpload = osSupportsOsDrivenUpload,
                // The build's development controls, from this build's adapter set: inert on every production
                // build, the control channel's on a rig build (`platformAdapters()`).
                devControls = adapters.devControls,
                // The entry ports: each registered by the host zone as this graph is composed, so a delivery in a
                // background wake finds its handler. The Swift shell forwards each callback to one of them.
                pushNotifications = pushNotifications,
                lifecycle = lifecycle,
                links = links,
                ui = adapters.ui,
                albumMapStore = albumMapStore,
                // A minted event routes into the host's join gate, so create and a scanned QR take one gate.
                onEventMinted = { eventId -> host.onEventCreated(eventId) },
                // The trigger-time membership re-read (migration step 12): every flow re-reads the
                // persisted config before acting — cross-process writes and a pre-first-unlock seed
                // never notify this process's StateFlow, and the reload retains the last good value
                // on an unreadable read (the pure `configAfterReload` rule).
                configRefresh = config,
                // The process's background time (`beginBackgroundTask`): what a push or a transfer wake holds across
                // its own work and its tail, and the only "time is up" those wakes get (capability `sync-status`).
                backgroundTime = IosBackgroundTime(log),
                // The operating system's scheduled wakes (`BGTaskScheduler`): the heartbeat the tail re-arms, and — by
                // the host zone's `listen`, as this graph is composed from `onLaunch` — its launch handler.
                wake = wakeAdapter,
                // The push registration's ports and token source (capability `receiving-photos`): `compose/`
                // builds the registration, its delivery/credential collector and the on-join re-PUT.
                push = PushPorts(
                    tokens = pushTokenSource,
                    record = IosPushRegistrationRecord(),
                ),
                // The upload arm's push receiver on the app-driven tier (a thunk — the tier controller
                // depends on this graph, so it must resolve lazily); null on iOS ≥26.1.
                log = log,
            ),
        )
    }

    /**
     * The backend — ONE `HttpBackend` over the platform's HTTP client, declaring this bundle's version, which every
     * backend call goes through. The credential and the backend's verdicts are the core's (`AppCore.backend`): this
     * root supplies only the port, so no call site can be wired without them.
     */
    private val backend: Backend by lazy { HttpBackend(darwinHttpClient(), backendHost, appMarketingVersion()) }

    // The app-side handle on the shared App-Group ledger: the app's own uploader's `LedgerWriter` (built by
    // `uploadCore`), the composed counts source's reads, and the membership reset family. On iOS ≥26.1 the
    // extension writes the same ledger from its own process; every write is one guarded transaction owned by
    // named code (capability `photo-sharing`; decision record `changes/both-uploaders-active`).
    private val ledgerStore: LedgerStore by lazy { LedgerService(databases) }

    // This process's SQLite databases, in the App-Group container. The stores open them on first use, never at
    // construction: building the composition opens no database (`docs/architecture.md`).
    private val databases: Databases by lazy { IosDatabases() }

    // --- Photo download / import (capability `receiving-photos`) ---
    // The app-written download store (idempotency + per-resource staging + the createdLocalId the
    // extension reads as its suppression set). The app is its one writer and the one process that migrates it.
    private val downloadStore: DownloadService by lazy { DownloadService(databases) }

    // The device-facing backend host (baked at compile time); shared by the backend port and the app's uploader.
    // Reads through
    // `:adapter:ios:ext-safe`'s [bakedUploadBase] — the same call the boot diagnostic makes, so a
    // banner that disagreed with the host the adapters use is impossible, and the absent-key
    // defaulting decision stays out of this wiring-only shell.
    private val backendHost: String by lazy { bakedUploadBase() }

    // --- Push notifications (capability `receiving-photos`) ---
    // The compile-time APNs environment (the generated Deployment.plist's `apnsEnv`): `sandbox` for
    // dev/sideloaded builds, `production` for TestFlight/App Store. The token itself is OS-delivered
    // (the Swift AppDelegate forwards it via [onPushToken], in answer to the ask [onLaunch] makes at every app
    // entry); the registration publishes it only when it differs from the last one the backend accepted. Read through the
    // adapter, not inline: what an absent key becomes is a decision, and this shell holds none.
    internal val pushTokenSource: PushTokenSource by lazy { PushTokenSource(bakedApnsEnv()) }


    // A silent push's own work (the download arm) and the tail its wake joins are both composed in the app graph
    // (`flow/SilentPush`, the tail runner); this root supplies no arm of its own.

    /**
     * The status host, assembled by the shared host composition on first touch: a minted event routes into its join
     * gate, and the control channel reads it. A cold background wake that merely touches [app] assembles none.
     */
    internal val host: StatusContainerHost get() = composed.host

    /**
     * The process's ONE cutoff formatter (capability `sync-status`): the device zone read once, here, from the
     * process's one clock — a formatter whose zone moved under a running screen would render one capture date two
     * ways. The status host reduces with it and the screen renders with it: the same instance.
     */
    private val cutoffFormatter: CutoffFormatter by lazy {
        CutoffFormatter(now = SystemClock::now, zone = process.clock.timeZone())
    }

    /** What this process knows about its scenes — shared by the UI and the lifecycle adapters, on the main thread. */
    private val sceneRecord: SceneRecord by lazy { SceneRecord() }

    /** The Compose scene SwiftUI hosts (`:adapter:ios:ui`). */
    internal val ui: IosUi by lazy { IosUi(sceneRecord, cutoffFormatter, log) }

    /** The app's foreground life: `didBecomeActive` / `willResignActive`, observed once the graph registers. */
    internal val lifecycle: IosLifecycle by lazy { IosLifecycle(sceneRecord, log) }

    /** Both halves of Universal-Link delivery and SwiftUI's `onOpenURL`. */
    internal val links: IosLinks by lazy { IosLinks(log) }

    /** APNs: the token request, the token and its failure, and every silent push. */
    internal val pushNotifications: IosPushNotifications by lazy { IosPushNotifications(log) }

    /**
     * The adapters that differ between a production and a rig build — chosen at BUILD time: `platformAdapters()` is
     * compiled from this module's `src/prod` or, only under `-Psnapsync.rig=true`, from the control channel's
     * (`docs/architecture.md`, "A build-time-only module is contained by compilation"). No flag is read here.
     */
    private val adapters: PlatformAdapters by lazy { platformAdapters(ui) }

    /**
     * Realize this object and **compose the graph** — called by the Swift `AppDelegate` from
     * `didFinishLaunchingWithOptions` (a plain statement, no decision). Composing registers every entry port's
     * handlers while Apple still accepts them: the wake adapter's `listen` is the heartbeat's `BGTask` launch-handler
     * registration, which Apple requires before launch finishes; the lifecycle adapter's installs the
     * `didBecomeActive` / `willResignActive` observers; and the composition asks the OS for the APNs token, as it does
     * again at every foreground entry (capability `receiving-photos`, "Registration timing — launch, join, and
     * rotation"). Composing builds nothing a locked device cannot (every core property is lazy; no database opens).
     */
    @PlatformEntry
    fun onLaunch() = log.invocation("onLaunch") {
        composed
        Unit
    }

    /** The app became active, as SwiftUI observes it — the scene generation SwiftUI binds to `.id(…)` ([IosUi]). */
    @PlatformEntry
    fun onSceneActive(): Int = ui.onSceneActive()

    /**
     * A restored/continued `NSUserActivity` arrived (both halves of Universal-Link delivery —
     * forwarded **whole** from the Swift scene delegate, which decides nothing; capability
     * `join-event`). The tested `model/` filter-and-dispatch keeps only a browsing-web activity
     * with a URL and forwards the **complete** `absoluteString` — the fragment carries the whole
     * payload; this wiring transcribes the activity's fields and branches on nothing.
     */
    @PlatformEntry
    fun onLaunchActivity(activity: NSUserActivity) = links.deliverUserActivity("onLaunchActivity", activity)

    /**
     * A link opened while the app is **already running**, via the scene delegate's `scene(_:continue:)`
     * — one of the two paths that can carry it. The other is [onSwiftUiOpenUrl], and neither is
     * sufficient alone.
     *
     * Measured working on iOS 26.5.2 twice (the 2026-07-16 session, and 2026-08-04 with 8 deliveries
     * and 8 hits) and on 26.6. **Measured NOT firing on iOS 18.7.9** while the app is already running,
     * from any source — Notes, WhatsApp, and Safari's smart banner alike (Bugsink `SNAPSYNC-39`,
     * `SNAPSYNC-43`, `SNAPSYNC-44`; builds 681/683, iPhone XS). There UIKit calls
     * [onSceneWillContinueActivity], announcing a continuation, and then calls neither this hook nor
     * [onSceneDidFailToContinueActivity]: it abandons the work without using its own failure path.
     *
     * Restoring SwiftUI's `.onOpenURL` delivered the link on that same OS build (687), which is why both
     * paths are wired. **Why that works is unexplained**, and is not asserted here: July's matrix
     * measured the modifier as failing with SwiftUI's own delegate in place, and it fires now with a
     * custom one installed, so "our delegate starves SwiftUI's" predicts the reverse. An earlier
     * revision of this KDoc said "on 18.7.9 the platform activates the app for a universal link and does
     * not call this hook" — a claim about the PLATFORM, and false as stated. Scope such claims to the
     * build and configuration measured, and prefer recording the outcome to explaining it. Expiry:
     * re-measure at the next iOS major, and whenever a delivery hook is added or removed.
     *
     * Its name is distinct from every other delivery entry's because a dump must be able to COUNT
     * deliveries: both paths are live, they overlap, and the gate absorbs the duplicate (capability
     * `join-event`). Collapsed names would make "delivered twice" unreadable.
     */
    @PlatformEntry
    fun onSceneContinueActivity(activity: NSUserActivity) =
        links.deliverUserActivity("onSceneContinueActivity", activity)

    /**
     * The scene connected, carrying [activities] restored/continued `NSUserActivity` values —
     * recorded **unconditionally, zero included** (`docs/architecture.md`, "Absence is never
     * silent"; spec `privacy-security`).
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
        lifecycle.deliverSceneEvent("onSceneWillConnect", params = "activities=$activities")

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
        lifecycle.deliverSceneEvent("onSceneWillContinueActivity", params = "type=$activityType")

    /**
     * UIKit **attempted** a continuation of [activityType] and could not finish it, with [description]
     * the platform's own rendering of the `NSError`.
     *
     * The third of `UISceneDelegate`'s continuation trio, and the only one that names a failure instead
     * of leaving an absence to interpret (law "Absence is never silent" — "nothing" and "couldn't tell"
     * are different answers). Measured never to fire on iOS 18.7.9: UIKit announces a continuation via
     * [onSceneWillContinueActivity] and then abandons it without reporting failure, so "UIKit refused"
     * and "UIKit never tried" were one observation for the length of the investigation. It is wired so
     * that they are two from now on.
     *
     * The error is rendered by the platform and forwarded as a string: formatting it in Swift would be
     * an untested decision under the shell gates, and `localizedDescription` is an encoding.
     */
    @PlatformEntry
    fun onSceneDidFailToContinueActivity(activityType: String, description: String) =
        lifecycle.deliverSceneEvent(
            "onSceneDidFailToContinueActivity",
            params = "type=$activityType error=$description",
            severity = Severity.Warn,
        )

    /**
     * The scene is entering the foreground, from its own delegate.
     *
     * Distinct from [onForeground], which Kotlin drives off the application-wide
     * `UIApplicationDidBecomeActive` notification: that one fires whether or not our scene delegate
     * is live, so it cannot answer "is the delegate being talked to?". This one can, and that is the
     * question a failed warm delivery leaves open.
     */
    @PlatformEntry
    fun onSceneWillEnterForeground() = lifecycle.deliverSceneEvent("onSceneWillEnterForeground")

    /** The scene became active, from its own delegate — see [onSceneWillEnterForeground]. */
    @PlatformEntry
    fun onSceneDidBecomeActive() = lifecycle.deliverSceneEvent("onSceneDidBecomeActive")

    /**
     * The scene was disconnected. Recorded because it changes which half of delivery a later link
     * takes: a disconnected scene makes the next link COLD (`onLaunchActivity`) even though the
     * process never died, and without this line that transition is invisible.
     */
    @PlatformEntry
    fun onSceneDidDisconnect() = lifecycle.deliverSceneEvent("onSceneDidDisconnect")

    /**
     * URL contexts were opened on the scene — the **custom-scheme** delivery path (capability
     * `join-event`).
     *
     * The `snapsync` scheme is retired and the Info.plist declares no `CFBundleURLTypes`, so this
     * should never fire. Forwarding it anyway is the point: if iOS 18 turns out to route a universal
     * link here, the log says so rather than the URL vanishing. It records only — it does NOT route
     * to `onOpenUrl`, because re-opening a second URL form is a behaviour change, not a diagnostic.
     */
    @PlatformEntry
    fun onSceneOpenUrlContexts(urls: List<String>) =
        lifecycle.deliverSceneEvent("onSceneOpenUrlContexts", params = "urls=${urls.size}")

    /**
     * A URL arrived at SwiftUI's `.onOpenURL` on the `WindowGroup` — **SwiftUI's** delivery path, as
     * opposed to the scene delegate's (capability `join-event`).
     *
     * This is the path that carries a link opened while the app is ALREADY RUNNING on iOS 18.7.9, where
     * `scene(_:continue:)` never fires however the link was opened. It is not a fallback: on that OS it
     * is the only warm delivery there is. It is also not sufficient alone — it fired for 2 of 4
     * deliveries on iOS 26.6 — which is why the scene delegate stays and why both are wired.
     *
     * SwiftUI hands the modifier a resolved `URL`, not an `NSUserActivity`, so this does NOT go through
     * [deliverUserActivity]: there is no activity type to filter and nothing to widen. The
     * `absoluteString` goes to the same [onOpenUrl] door every other path uses, so the tested `model/`
     * codec still decides what the URL is — a foreign origin is rejected there, as it always was.
     *
     * Its own entry name matters more here than anywhere: this path and [onLaunchActivity] both fire on
     * a cold launch, so a dump has to be able to COUNT deliveries. The gate absorbs the duplicate
     * (capability `join-event`); the names are what prove it did. Measured (build 687): the same URL arrived
     * twice on an iOS 18.7.9 cold launch (~130 ms apart) and twice on iOS 26.6 both running (8 ms) and cold
     * (105 ms). No contract clause asserts it. See changes/archive/2026-07-16-migrate-to-universal-links.
     */
    @PlatformEntry
    fun onSwiftUiOpenUrl(url: String) = links.deliverOpenUrl("onSwiftUiOpenUrl", url)

    /**
     * The operating system is handing back finished background transfers for the session [channel] — forwarded whole
     * from the Swift `AppDelegate`'s `handleEventsForBackgroundURLSession`. Routed by the adapter module's
     * [BackgroundSessions] to the session it names, whose handlers (registered as the graph is composed) hold
     * [completion] across the wake. A session's events arrive through the `Upload` and `Download` event ports; this is
     * the shell's reach to their adapters' shared relaunch dispatcher.
     */
    @PlatformEntry
    fun onBackgroundTransfers(channel: String, completion: () -> Unit) =
        backgroundSessions.handleEvents(channel, completion)

    /**
     * APNs registration **failed** (capability `receiving-photos`), forwarded from the Swift
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
    fun onPushTokenFailure(description: String) = pushNotifications.deliverTokenFailure(description)

    /** APNs issued this device's token — forwarded from the `AppDelegate`, rendered as lowercase hex there. */
    @PlatformEntry
    fun onPushToken(hex: String) = pushNotifications.deliverToken(hex)

    /**
     * A silent push arrived — its `userInfo` forwarded **whole** from the `AppDelegate`, and its fetch handler as
     * [completion], released once after the push's own work or at once when the process's background time is up.
     */
    @PlatformEntry
    fun onSilentPush(payload: Map<Any?, *>, completion: () -> Unit) =
        pushNotifications.deliverMessage(payload, completion)

    /**
     * Provision an event id — the shared path for both a scanned or typed event link and a freshly created
     * event. Persists the config (the container's `ConfigSource` is this instance), re-reads the gallery
     * total and the storage-truth status sources, then runs the upload arm's **join** transition (the tested,
     * stateless `UploadTransitions`). At a first join or a switch the Provision flow also loads the
     * upload ledger from the device's stored-file listing (`photo-sharing`).
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
    // The permission-grant subscriptions (upload-arm start + event-album ensure) live in `compose/`
    // (`AppCore.installPermissionSubscriptions`, migration step 8) and are installed ONLY from the
    // [host] assembly above — never on mere [AppCore] construction, so a cold background wake starts
    // no producer off the StateFlow's replay. Both fire only on a *transition* to GRANTED, so neither
    // can rescue a membership provisioned while access was already granted — the provision flow owns
    // that case.

    /**
     * The operating system's scheduled wakes — one per process, since its `listen` registers the `BGTask` launch
     * handler and a second registration raises. `internal` so the control channel can deliver a task it plays the OS
     * for (`/os onBackgroundTask`) until the entry surface becomes event ports (11g).
     */
    internal val wakeAdapter: IosWake by lazy { IosWake(log) }

    /**
     * The registration port's adapter, chosen by compilation target (capability `background-upload`), on every OS.
     * `internal` so the control channel reads the registration through the very port the app registers through,
     * rather than asking PhotoKit a second time and possibly getting a different answer. Not exported to the ObjC
     * framework header.
     */
    internal val extensionRegistry: ExtensionRegistry by lazy { platformExtensionRegistry(log) }

    // The ONE gallery this process holds: every photo-library read and album write the status total, the join
    // preview, the download guard, the event album and the app's uploader make.
    private val gallery: IosGallery by lazy { IosGallery(IosGalleryReader(), permission, scope) }

    /** The app's uploader transport — one per process, since it owns the upload session's delegate. */
    private val uploadAdapter: IosUrlSessionUploadPlatform by lazy { IosUrlSessionUploadPlatform(log, UPLOAD_SESSION_ID) }

    /** The platform's background downloads — one per process, since it owns the download session's delegate. */
    private val downloadAdapter: IosDownload by lazy { IosDownload() }

    /**
     * Where `handleEventsForBackgroundURLSession` goes: routed by the identifier the OS named, in the adapter module.
     * Composes the graph first, so the sessions' handlers are registered before the session they bring up delivers.
     */
    private val backgroundSessions: BackgroundSessions by lazy {
        composed
        BackgroundSessions(uploadAdapter, downloadAdapter)
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
 * The **composition lane** (`docs/architecture.md`, law "Dispatcher lanes are fixed by the
 * composition"): the one thread every live-core coroutine in this process runs on.
 *
 * **Why not the main thread.** Whether a port call blocks *the main thread* is a property of the
 * caller's dispatcher, not of the adapter — so it cannot be judged where the call is written, and it
 * was not: 21 of 23 iOS adapter files touching a blocking platform API hop nowhere. Owning the
 * decision here makes a blocking call off-main by construction. Forcing proof: build 521 died on an
 * iPhone11,2 / iOS 18.7.9 with `assetsd` wedged inside `fetchPersistentChangesSinceToken`, 0.071 s of
 * app CPU across the whole watchdog allowance — blocked, not busy (`IosGalleryReader`).
 *
 * **Why exactly one thread.** `Dispatchers.Main` is single-threaded and core code relies on that for
 * mutual exclusion — the selection observer's lock-free register/unregister says
 * so, and whatever else assumes it cannot be
 * enumerated. One thread changes which thread and nothing else; a pool would silently turn every
 * un-enumerated assumption into a race.
 *
 * **Why its own thread rather than a slice of [Dispatchers.Default].** Orbit's event loop reduces
 * presentation state on `Default`. A blocked platform call parked in that pool would stall the UI's
 * own updates — an OS kill traded for a frozen screen. `Dispatchers.IO` would be the obvious home and
 * is **`internal`** on Kotlin/Native (coroutines 1.10.2): it is in the klib but not callable, measured
 * by compile, not read off a symbol table. Expiry trigger: a coroutines release that publishes it.
 *
 * **Why pinned at `QOS_CLASS_USER_INITIATED`.** Its blocking platform calls are XPC round-trips (PhotoKit
 * commits and fetches, the Keychain, SQLite on the App Group) and the calling thread's QoS propagates over
 * them: measured on an SE2, PhotoKit commits issued at `QOS_CLASS_BACKGROUND` took ~400 ms against ~60 ms (6–7×).
 * A thread that inherits its class would carry whatever the wake it was created in carried. The pin is the
 * lane's first task, logged once with the class it replaced ([newUserInitiatedLane]).
 *
 * The lane is never closed. That is the requirement here, not the hazard: this scope lives as long as the
 * process, and closing its dispatcher is what must not happen.
 */
private val compositionLane = newUserInitiatedLane(name = "snapsync-composition")
