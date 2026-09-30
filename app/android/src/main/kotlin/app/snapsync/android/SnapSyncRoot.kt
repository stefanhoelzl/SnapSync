package app.snapsync.android

import android.app.Application
import app.snapsync.android.attest.AndroidDeviceIntegrity
import app.snapsync.android.backend.androidHttpClient
import app.snapsync.android.download.AndroidDownload
import app.snapsync.android.process.AndroidProcessInfo
import app.snapsync.android.push.AndroidPushNotifications
import app.snapsync.android.push.FirebaseConfig
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
import app.snapsync.android.buildinfo.AndroidBuildInfo
import app.snapsync.compose.AppPorts
import app.snapsync.compose.DevicePorts
import app.snapsync.compose.NoCrashReporter
import app.snapsync.compose.NoEntryContext
import app.snapsync.compose.NoProcessMetrics
import app.snapsync.compose.ProcessPorts
import app.snapsync.host.ComposedApp
import app.snapsync.host.snapSyncHost
import app.snapsync.http.HttpBackend
import app.snapsync.model.EntryScope
import app.snapsync.model.PlatformEntry
import app.snapsync.model.invocation
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.time.SystemClock
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * **The Android composition root** (`docs/architecture.md`, "One shared composition"): ONE per process, built in
 * `Application.onCreate` — the shared host composition (`snapSyncHost`, whose first act sets the process up) over the
 * ports this build's adapter set hands back ([platformAdapters]) and nothing else. Android runs the activity,
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
        CutoffFormatter(now = ports.clock::now, zone = ports.clock.timeZone())
    }

    /**
     * The activity the member is looking at, tracked from the process's start so a system surface — the permission
     * dialog, the share sheet — has something to present from, and a return from Settings re-reads the photo grant.
     */
    private val foreground = ForegroundActivity(application)

    /** The photo grant, and the dialog and selection sheet that change it. */
    private val photoPermission: AndroidPhotoPermission by lazy { AndroidPhotoPermission(application, foreground) }

    /** The Firebase project this build's pushes come from — the resolved deployment's public values. */
    private val firebase = FirebaseConfig(
        projectId = BuildConfig.FIREBASE_PROJECT_ID,
        applicationId = BuildConfig.FIREBASE_APPLICATION_ID,
        apiKey = BuildConfig.FIREBASE_API_KEY,
        senderId = BuildConfig.FIREBASE_SENDER_ID,
    )

    /** This process's REAL adapters — the systems Android has one for — each built on first use. */
    private val real: DevicePorts = DevicePorts(
        clock = lazyOf(SystemClock),
        files = lazy { AndroidFiles(application) },
        databases = lazy { AndroidDatabases(application) },
        preferences = lazy { AndroidPreferences(application) },
        secureStore = lazy { AndroidSecureStore(application) },
        // ANDROID_ID, as a v5 UUID: it survives a reinstall, so a first mint adopts it.
        platformDeviceId = lazy { AndroidPlatformDeviceId(application) },
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
        download = lazy { AndroidDownload(application) },
        pushNotifications = lazy { AndroidPushNotifications(application, firebase) },
        processInfo = lazy { AndroidProcessInfo(application) },
        // No crash reporter is linked until phase 5: this build carries no destination, so nothing would start anyway.
        crashReporter = lazyOf(NoCrashReporter),
    )

    /** The adapters that differ between a production and a rig build, chosen at BUILD time. */
    private val adapters: PlatformAdapters = platformAdapters(this, real)

    private val ports: DevicePorts get() = adapters.ports

    /** This process's per-process ports — set up by the composition as its first act. */
    private val processPorts: ProcessPorts by lazy {
        ProcessPorts(
            crashReporter = ports.crashReporter,
            processMetrics = NoProcessMetrics,
            logSinks = listOf(LogcatSink()),
            files = ports.files,
            clock = ports.clock,
            entryContext = NoEntryContext,
            build = AndroidBuildInfo(
                appVersion = BuildConfig.APP_VERSION,
                buildNumber = BuildConfig.VERSION_CODE.toString(),
                uploadHost = BuildConfig.UPLOAD_BASE,
                bootLines = listOf("=== app process start ===") + adapters.bootLines,
                // An FCM token belongs to the Firebase project that issued it: that project is its push environment.
                apnsEnvironment = firebase.projectId,
            ),
        )
    }

    // The app-scope error boundary: a throwable no coroutine handled is logged, and the app lives — errors reduce into
    // state and never crash the shell. The main lane: the composition's, as on iOS.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            CoroutineExceptionHandler { _, t -> log.e(t) { "uncaught in app scope — logged, not fatal" } },
    )

    /** The core AND the status host over it, from the shared host composition. */
    private val composed: ComposedApp by lazy {
        snapSyncHost(
            scope = scope,
            cutoffFormatter = cutoffFormatter,
            ports = AppPorts(
                process = processPorts,
                databases = ports.databases,
                preferences = ports.preferences,
                secureStore = ports.secureStore,
                platformDeviceId = ports.platformDeviceId,
                photoAccess = ports.photoAccess,
                gallery = ports.gallery,
                systemUi = ports.systemUi,
                download = ports.download,
                backend = ports.backend,
                integrity = ports.integrity,
                // The app's uploader transport — the only uploader Android has (no OS-driven tier).
                appUpload = ports.appUpload,
                backgroundTime = ports.backgroundTime,
                wake = ports.wake,
                extensionRegistry = ports.extensionRegistry,
                devControls = adapters.devControls,
                pushNotifications = ports.pushNotifications,
                lifecycle = ports.lifecycle,
                links = ports.links,
                ui = adapters.ui.value,
                processInfo = ports.processInfo,
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
}
