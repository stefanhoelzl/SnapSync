package app.snapsync.jvm

import app.snapsync.compose.AlbumLookupFailure
import app.snapsync.compose.AppCore
import app.snapsync.compose.AppPorts
import app.snapsync.compose.NoEntryContext
import app.snapsync.compose.NoProcessMetrics
import app.snapsync.compose.ProcessPorts
import app.snapsync.compose.ProcessServices
import app.snapsync.compose.PushPorts
import app.snapsync.compose.UploadPorts
import app.snapsync.compose.UploadRecordPorts
import app.snapsync.compose.UploaderProcess
import app.snapsync.compose.snapSyncExtension
import app.snapsync.compose.snapSyncProcess
import app.snapsync.compose.uploadCore
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.host.ComposedApp
import app.snapsync.host.snapSyncHost
import app.snapsync.identity.NoPlatformDeviceId
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.config.ConfigService
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.gallery.GalleryDiscovery
import app.snapsync.services.identity.AttestState
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.logs.LogTailService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.push.PushRegistrationRecord
import app.snapsync.services.push.PushTokenSource
import app.snapsync.services.staging.StagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.time.Clock

/**
 * **The JVM root** (`docs/testing.md`, "The JVM root"): the app composed on the JVM exactly as `SnapSyncRoot` composes
 * it on the phone — the process's services first (`snapSyncProcess`), then the shared host composition
 * (`snapSyncHost`) — over the adapters [adapters] builds from [durable] for each launch. The upload extension's entry
 * port is registered on the launch's extension host the way its own root registers it.
 *
 * [durable] is whatever survives a process: the caller's choice, usually [JvmMocks]. [relaunch] is process death and a
 * cold launch — every collector and in-flight launch of the running app ends, and a new app is composed over a fresh
 * set of adapters over the same [durable]. Nothing is forced at construction that the phone does not force at process
 * start: the core, its status host and the cycle are built on first use.
 *
 * What differs from the phone, stated:
 * - **One JVM, two processes' worth of faces.** The extension's [cycle] is composed beside the app, over the app's
 *   admission ([UploaderProcess.App]) and its album coordinator — the JVM carries no OS-driven mechanism, so its one
 *   cycle is the app tier's, invoked through the extension's entry port. Carried over from the world it replaces.
 * - **The push token survives a relaunch**, as the OS re-delivers it to every launch on a device.
 * - **The screen's "now" is the wall clock**, while the core reads the launch's [JvmAdapters.clock], so a status screen
 *   renders dates a person would see.
 */
class JvmApp<D>(
    /** The caller's scope, which owns every launch's work: the app runs under a child job of it. */
    private val scope: CoroutineScope,
    /** What survives the app's process — handed to [adapters] at every launch. */
    val durable: D,
    /** One launch's adapters over [durable]. */
    private val adapters: (D) -> JvmAdapters,
) {
    private var appJob: Job = Job(scope.coroutineContext[Job])
    private var appScope: CoroutineScope = CoroutineScope(scope.coroutineContext + appJob)

    /** This launch's adapters. */
    var ports: JvmAdapters = adapters(durable)
        private set

    /** The OS-delivered push token — kept across launches, as the OS re-delivers it to each. */
    private val pushTokens: PushTokenSource = PushTokenSource(ports.build.apnsEnvironment)

    private var launch: Launch = Launch(ports)

    /** The running app — its core and, on first touch of [host], its status host. */
    val composed: ComposedApp get() = launch.composed

    /** The running app's core. */
    val core: AppCore get() = launch.composed.core

    /** The running app's status host, assembled on first touch as a foreground launch assembles it. */
    val host: StatusContainerHost get() = launch.composed.host

    /** This launch's process services. */
    val process: ProcessServices get() = launch.process

    /** The upload extension's cycle — the shared `uploadCore` assembly, composed on first use. */
    val cycle: UploadCycle get() = launch.cycle

    /** Process death and a cold launch: a new app over a fresh set of adapters over the same [durable]. */
    fun relaunch() {
        appJob.cancel()
        appJob = Job(scope.coroutineContext[Job])
        appScope = CoroutineScope(scope.coroutineContext + appJob)
        ports = adapters(durable)
        launch = Launch(ports)
    }

    /** One process's composition over [ports] — every service built anew, as a process builds them. */
    private inner class Launch(private val ports: JvmAdapters) {
        val config = ConfigService(ports.files, ports.clock)
        val ledger = LedgerService(ports.databases)
        val downloadStore = DownloadService(ports.databases)
        val manifestStore = DeviceManifestService(ports.files)

        val process: ProcessServices = snapSyncProcess(
            ProcessPorts(
                crashReporter = ports.crashReporter,
                processMetrics = NoProcessMetrics,
                logSinks = emptyList(),
                files = ports.files,
                clock = ports.clock,
                entryContext = NoEntryContext,
                dsn = ports.build.dsn,
                bootLines = emptyList(),
                // Kermit's writer list is JVM-global and this is one of many processes in the JVM.
                ownsGlobalLogger = false,
            ),
        )

        val composed: ComposedApp = snapSyncHost(appScope, process, appPorts(), cutoffFormatter())

        val cycle: UploadCycle by lazy { uploadCore(appScope, process, uploadPorts) }

        private val uploadPorts: UploadPorts by lazy {
            UploadPorts(
                process = UploaderProcess.App({ composed.core.appUploadAdmission() }, { composed.core.photoPermission.value }),
                config = config,
                deviceIdentity = PersistedDeviceIdentity(DeviceIdentityRole.READ_ONLY, ports.secureStore, NoPlatformDeviceId()),
                host = ports.build.host,
                appVersion = ports.build.appVersion.value.orEmpty(),
                ledger = ledger,
                upload = ports.cycleUpload,
                gallery = ports.cycleGallery,
                discovery = GalleryDiscovery(ports.cycleGallery),
                selectionScope = { composed.core.selectionScope() },
                manifestStore = manifestStore,
                manifestPublisher = composed.core.backend.manifest,
                suppression = downloadStore,
                albumManager = GalleryAlbums(ports.cycleGallery),
                albumLookupFailure = AlbumLookupFailure.AdmitOnDoubt,
                albumCoordinator = composed.core.albumCoordinator,
                token = { null },
                freshToken = { null },
            )
        }

        init {
            // The extension's root registers its entry port on the OS's host; the thunks force nothing until invoked.
            snapSyncExtension(ports.extensionHost, ports = { uploadPorts }, cycle = { cycle }, rereadCredential = {})
        }

        private fun appPorts(): AppPorts = AppPorts(
            config = config,
            photoAccess = ports.photoAccess,
            gallery = ports.gallery,
            systemUi = ports.systemUi,
            // The in-memory platform has no main thread: platform-UI commands run on the composition's own lane.
            uiLane = scope.coroutineContext.minusKey(Job),
            uploadRecord = UploadRecordPorts(ledger = ledger),
            downloadStore = downloadStore,
            stagedBytes = StagingService(ports.files),
            download = ports.download,
            backend = ports.backend,
            manifestStore = manifestStore,
            integrity = ports.integrity,
            attestStore = AttestState(ports.secureStore),
            deviceIdentity = PersistedDeviceIdentity(DeviceIdentityRole.MINTING, ports.secureStore, NoPlatformDeviceId()),
            appStoreUrl = ports.build.appStoreUrl,
            appDrivenUpload = { ports.appDrivenUpload },
            appUpload = ports.appUpload,
            backgroundTime = ports.backgroundTime,
            wake = ports.wake,
            extensionRegistry = ports.extensionRegistry,
            devControls = ports.devControls,
            pushNotifications = ports.pushNotifications,
            lifecycle = ports.lifecycle,
            links = ports.links,
            ui = ports.ui,
            albumMapStore = AlbumMapService(ports.preferences, ports.secureStore),
            // A minted event opens this app's join gate, as the iOS root routes it.
            onEventMinted = { eventId -> composed.host.onEventCreated(eventId) },
            push = PushPorts(tokens = pushTokens, record = PushRegistrationRecord(ports.files)),
            processInfo = ports.processInfo,
            deviceLogs = LogTailService(ports.files),
            log = ports.build.log,
        )

        /** The zone is read once from the launch's clock; "now" is the wall clock (see the class's deviations). */
        private fun cutoffFormatter(): CutoffFormatter = CutoffFormatter(now = Clock.System::now, zone = ports.clock.timeZone())
    }
}
