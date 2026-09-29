package app.snapsync.android

import android.app.Application
import app.snapsync.android.attest.AndroidDeviceIntegrity
import app.snapsync.android.backend.androidHttpClient
import app.snapsync.android.gallery.AndroidGallery
import app.snapsync.android.link.AndroidLinks
import app.snapsync.android.logging.LogcatSink
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.android.scene.AndroidLifecycle
import app.snapsync.android.scene.AndroidUi
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.android.storage.AndroidDatabases
import app.snapsync.android.storage.AndroidFiles
import app.snapsync.android.storage.AndroidPlatformDeviceId
import app.snapsync.android.storage.AndroidPreferences
import app.snapsync.android.storage.AndroidSecureStore
import app.snapsync.android.systemui.AndroidSystemUi
import app.snapsync.android.upload.AndroidExtensionRegistry
import app.snapsync.android.work.AndroidBackgroundTime
import app.snapsync.android.work.AndroidUpload
import app.snapsync.android.work.AndroidWake
import app.snapsync.compose.AppCore
import app.snapsync.compose.AppPorts
import app.snapsync.compose.AppUploaderPorts
import app.snapsync.compose.DevicePorts
import app.snapsync.compose.NoEntryContext
import app.snapsync.compose.NoProcessMetrics
import app.snapsync.compose.ProcessPorts
import app.snapsync.compose.ProcessServices
import app.snapsync.compose.PushPorts
import app.snapsync.compose.UploadRecordPorts
import app.snapsync.compose.appUploader
import app.snapsync.compose.snapSyncProcess
import app.snapsync.host.ComposedApp
import app.snapsync.host.snapSyncHost
import app.snapsync.http.HttpBackend
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.model.EntryScope
import app.snapsync.model.PlatformEntry
import app.snapsync.model.invocation
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.config.ConfigService
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.identity.AttestState
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.logs.LogTailService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.push.PushRegistrationRecord
import app.snapsync.services.push.PushTokenSource
import app.snapsync.services.staging.StagingService
import app.snapsync.time.SystemClock
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * **The Android composition root** (`docs/architecture.md`, "One shared composition"): ONE per process, built in
 * `Application.onCreate` — the process's services first (`snapSyncProcess`), then the shared host composition
 * (`snapSyncHost`) over the ports this build's adapter set hands back ([platformAdapters]). Android runs the activity,
 * and in later phases the workers and the push service, in this one process, so they are all entry ports on this one
 * composition; there is no second root.
 *
 * Nothing is forced at construction that a background start must not build: the core and every service are lazy, and
 * the status host is assembled only when an activity pulls the screen ([AndroidUi.content]) — a process a worker starts
 * builds no UI.
 */
class SnapSyncRoot(internal val application: Application) {

    private val log = Logger.withTag("SnapSyncRoot")

    /** The process's foreground life. `internal` so a rig build's `/os` verbs deliver through it. */
    internal val lifecycle: AndroidLifecycle by lazy { AndroidLifecycle() }

    /** The links the activity is opened with. `internal` so the activity, and a rig build's `/os` verb, deliver them. */
    internal val links: AndroidLinks by lazy { AndroidLinks(log) }

    /** The screen an activity pulls. */
    internal val ui: AndroidUi by lazy { AndroidUi(cutoffFormatter, log) }

    /**
     * The process's ONE cutoff formatter (capability `sync-status`): the zone read once from the process's clock; the
     * status host reduces with it and the screen renders with it.
     */
    private val cutoffFormatter: CutoffFormatter by lazy {
        CutoffFormatter(now = process.clock::now, zone = process.clock.timeZone())
    }

    /**
     * The activity the member is looking at, tracked from the process's start so a system surface — the permission
     * dialog, the share sheet — has something to present from, and a return from Settings re-reads the photo grant.
     */
    private val foreground = ForegroundActivity(application)

    /** The photo grant, and the dialog and selection sheet that change it. */
    private val photoPermission: AndroidPhotoPermission by lazy { AndroidPhotoPermission(application, foreground) }

    /** This process's REAL adapters — the systems Android has one for — each built on first use. */
    private val real: DevicePorts = DevicePorts(
        clock = lazyOf(SystemClock),
        files = lazy { AndroidFiles(application) },
        databases = lazy { AndroidDatabases(application) },
        preferences = lazy { AndroidPreferences(application) },
        secureStore = lazy { AndroidSecureStore(application) },
        integrity = lazy { AndroidDeviceIntegrity() },
        backend = lazy { HttpBackend(androidHttpClient(), BuildConfig.UPLOAD_BASE, BuildConfig.APP_VERSION) },
        lifecycle = lazy { lifecycle },
        links = lazy { links },
        ui = lazy { ui },
        gallery = lazy { AndroidGallery(application, photoPermission, scope) },
        photoAccess = lazy { photoPermission },
        systemUi = lazy { AndroidSystemUi(application, foreground) },
        extensionRegistry = lazyOf(AndroidExtensionRegistry),
        wake = lazy { AndroidWake(application) },
        backgroundTime = lazy { AndroidBackgroundTime(application) },
        // Its transfers hold this launch's background time — the real one, or the mock a rig launch chose.
        appUpload = lazy { AndroidUpload(application, ports.backgroundTime) },
    )

    /** The adapters that differ between a production and a rig build, chosen at BUILD time. */
    private val adapters: PlatformAdapters = platformAdapters(this, real)

    private val ports: DevicePorts get() = adapters.ports

    /** This process's per-process services — every root's first act. */
    private val process: ProcessServices = snapSyncProcess(
        ProcessPorts(
            crashReporter = ports.crashReporter,
            processMetrics = NoProcessMetrics,
            logSinks = listOf(LogcatSink()),
            files = ports.files,
            clock = ports.clock,
            entryContext = NoEntryContext,
            dsn = null,
            bootLines = listOf("=== app process start ===") + adapters.bootLines,
            ownsGlobalLogger = true,
        ),
    )

    // The app-scope error boundary: a throwable no coroutine handled is logged, and the app lives — errors reduce into
    // state and never crash the shell. The main lane: the composition's, as on iOS.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            CoroutineExceptionHandler { _, t -> log.e(t) { "uncaught in app scope — logged, not fatal" } },
    )

    private val config: ConfigService by lazy { ConfigService(ports.files, process.clock) }
    private val ledger: LedgerService by lazy { LedgerService(ports.databases) }
    private val downloadStore: DownloadService by lazy { DownloadService(ports.databases) }
    private val manifestStore: DeviceManifestService by lazy { DeviceManifestService(ports.files) }

    /** The core AND the status host over it, from the shared host composition. */
    private val composed: ComposedApp by lazy {
        snapSyncHost(
            scope = scope,
            process = process,
            cutoffFormatter = cutoffFormatter,
            ports = AppPorts(
                config = config,
                photoAccess = ports.photoAccess,
                gallery = ports.gallery,
                systemUi = ports.systemUi,
                uiLane = Dispatchers.Main,
                uploadRecord = UploadRecordPorts(ledger = ledger),
                downloadStore = downloadStore,
                stagedBytes = StagingService(ports.files),
                download = ports.download,
                backend = ports.backend,
                manifestStore = manifestStore,
                integrity = ports.integrity,
                attestStore = AttestState(ports.secureStore),
                deviceIdentity = PersistedDeviceIdentity(
                    DeviceIdentityRole.MINTING,
                    ports.secureStore,
                    AndroidPlatformDeviceId(application),
                ),
                appStoreUrl = null,
                // The app's uploader over the shared cycle — the only uploader Android has (no OS-driven tier).
                appDrivenUpload = {
                    adapters.appDrivenUpload {
                        appUploader(
                            app,
                            AppUploaderPorts(
                                config = config,
                                grant = PhotoGrantRead { ports.photoAccess.permission.value },
                                host = BuildConfig.UPLOAD_BASE,
                                appVersion = BuildConfig.APP_VERSION,
                            ),
                        )
                    }
                },
                appUpload = ports.appUpload,
                backgroundTime = ports.backgroundTime,
                wake = ports.wake,
                extensionRegistry = ports.extensionRegistry,
                devControls = adapters.devControls,
                pushNotifications = ports.pushNotifications,
                lifecycle = ports.lifecycle,
                links = ports.links,
                ui = adapters.ui.value,
                albumMapStore = AlbumMapService(ports.preferences, ports.secureStore),
                // A minted event routes into the host's join gate, so create and a scanned QR take one gate.
                onEventMinted = { eventId -> host.onEventCreated(eventId) },
                // The push token's environment is APNs vocabulary; Android's push service replaces it.
                push = PushPorts(tokens = PushTokenSource(PUSH_ENVIRONMENT), record = PushRegistrationRecord(ports.files)),
                processInfo = ports.processInfo,
                deviceLogs = LogTailService(ports.files),
                log = log,
            ),
        )
    }

    /** The composed core. `internal` for a rig build's channel, which reaches it as a thunk. */
    internal val app: AppCore get() = composed.core

    /** The status host, assembled on first touch. `internal` for a rig build's channel. */
    internal val host: StatusContainerHost get() = composed.host

    /**
     * **Compose the graph** — called from `Application.onCreate`, on the main thread: composing registers every entry
     * port's handlers, the lifecycle's process observer among them, before any activity can resume.
     */
    @PlatformEntry
    fun onLaunch() = log.invocation(EntryScope.None, "onLaunch") {
        composed
        adapters.afterLaunch()
    }

    private companion object {
        const val PUSH_ENVIRONMENT = "sandbox"
    }
}
