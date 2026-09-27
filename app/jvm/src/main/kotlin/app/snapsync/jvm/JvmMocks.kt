package app.snapsync.jvm

import app.snapsync.http.HttpBackend
import app.snapsync.mock.BackendMock
import app.snapsync.mock.BackgroundTimeMock
import app.snapsync.mock.ClockMock
import app.snapsync.mock.CrashReporterMock
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.DeclaredVersion
import app.snapsync.mock.DevControlsMock
import app.snapsync.mock.DeviceIntegrityMock
import app.snapsync.mock.DownloadSessionMock
import app.snapsync.mock.ExtensionHostMock
import app.snapsync.mock.ExtensionRegistryMock
import app.snapsync.mock.FileSystemMock
import app.snapsync.mock.LifecycleMock
import app.snapsync.mock.LinksMock
import app.snapsync.mock.PhotoLibraryMock
import app.snapsync.mock.PreferencesMock
import app.snapsync.mock.ProcessInfoMock
import app.snapsync.mock.PushServiceMock
import app.snapsync.mock.ScreenMock
import app.snapsync.mock.SecureStoreMock
import app.snapsync.mock.SystemUiMock
import app.snapsync.mock.UploadNetwork
import app.snapsync.mock.UploadQueueMock
import app.snapsync.mock.UploadSessionMock
import app.snapsync.mock.WakeMock
import app.snapsync.model.ApnsPushToken
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.EventCreated
import app.snapsync.model.EventMeta
import app.snapsync.model.EventRenamed
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.MintRequest
import app.snapsync.model.RenewRequest
import app.snapsync.model.Reply
import app.snapsync.model.SecureSlots
import app.snapsync.model.UnionAsset
import app.snapsync.ports.Backend
import io.ktor.client.HttpClient

/**
 * **The device as mocks** — the durable state a [JvmApp] over mocks keeps across a relaunch: one mock per external
 * system (`docs/testing.md`, "Mocks"), each holding what that system keeps, each handing a launch its port face
 * ([adapters]) and its operator face to whoever plays it.
 *
 * [network] is what an OS upload crosses: by default the backend mock's own byte route, so a completed job's bytes land
 * in [backend]; a caller that puts the real `api/` behind the backend port passes a network that reaches it.
 */
class JvmMocks(
    /** This device's id, already in its Keychain — a device whose app has launched before. */
    val ownDeviceId: String = DEFAULT_DEVICE_ID,
    /** Whether this build honours an invite link's dev/test hints — a shipped build ignores them. */
    inviteLinkHints: InviteLinkHints = InviteLinkHints.Ignored,
    network: UploadNetwork? = null,
) {
    val backend = BackendMock()
    val library = PhotoLibraryMock()
    val disk = FileSystemMock()
    val databases = DatabasesMock()
    val preferences = PreferencesMock()
    val keychain = SecureStoreMock(mapOf(SecureSlots.DEVICE_ID to ownDeviceId))
    val enclave = DeviceIntegrityMock()
    val crashReporter = CrashReporterMock()
    val processInfo = ProcessInfoMock()
    val clock = ClockMock()
    val wakes = WakeMock()
    val backgroundTime = BackgroundTimeMock()
    val extensionRegistry = ExtensionRegistryMock()
    val uploadQueue = UploadQueueMock(network ?: UploadNetwork { url, headers, _ -> backend.operator.receive(url, headers) })
    val uploadSession = UploadSessionMock()
    val downloads = DownloadSessionMock(disk)
    val lifecycle = LifecycleMock()
    val links = LinksMock()
    val pushService = PushServiceMock()
    val screen = ScreenMock()
    val devControls = DevControlsMock(inviteLinkHints)
    val extensionHost = ExtensionHostMock()
    val systemUi = SystemUiMock()

    /**
     * One launch's adapters over these mocks. [attests] is whether the app process has App Attest (a simulator has
     * not); [backend] is the backend port — the mock's own face by default, or one reaching the real `api/`.
     */
    fun adapters(build: JvmBuild, attests: Boolean, backend: Backend = this.backend.port(build.appVersion)): JvmAdapters =
        JvmAdapters(
            build = build,
            clock = clock.port(),
            crashReporter = crashReporter.port(),
            extensionCrashReporter = crashReporter.unobservedPort(),
            files = disk.port(),
            extensionFiles = disk.port(privateArea = false),
            databases = databases.port(),
            preferences = preferences.port(),
            secureStore = keychain.port(),
            integrity = enclave.port(available = attests),
            processInfo = processInfo.port(),
            backend = backend,
            backgroundTime = backgroundTime.port(),
            wake = wakes.port(),
            extensionRegistry = extensionRegistry.port(),
            lifecycle = lifecycle.port(),
            links = links.port(),
            pushNotifications = pushService.port(),
            ui = screen.port(),
            devControls = devControls.port(),
            extensionHost = extensionHost.port(),
            gallery = library.port(),
            cycleGallery = library.port(),
            photoAccess = library.photoAccess(),
            appUpload = uploadSession.port(),
            cycleUpload = uploadQueue.port(),
            download = downloads.port(),
            systemUi = systemUi.port(),
            appDrivenUpload = OperatorDrivenUploads,
        )

    companion object {
        /** The device id a mocked device carries unless told otherwise. */
        const val DEFAULT_DEVICE_ID: String = "00000000-0000-4000-9000-0000000000a1"
    }
}

/**
 * The backend port over HTTP for a build whose declared version an operator may change in place: the production
 * [HttpBackend] over [client], rebuilt per call around the version [declared] holds now. It holds no state, so that is
 * all it costs; every answer is `HttpBackend`'s.
 */
class VersionedHttpBackend(
    private val client: HttpClient,
    private val base: String,
    private val declared: DeclaredVersion,
) : Backend {
    private fun http() = HttpBackend(client, base, declared.value.orEmpty())

    override suspend fun challenge(): Reply<String> = http().challenge()
    override suspend fun mintToken(req: MintRequest): Reply<String> = http().mintToken(req)
    override suspend fun renewToken(req: RenewRequest): Reply<String> = http().renewToken(req)
    override suspend fun createEvent(token: String?, req: CreateEventRequest): Reply<EventCreated> = http().createEvent(token, req)
    override suspend fun getEvent(eventId: String): Reply<EventMeta> = http().getEvent(eventId)
    override suspend fun renameEvent(token: String?, eventId: String, name: String): Reply<EventRenamed> =
        http().renameEvent(token, eventId, name)
    override suspend fun joinEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> =
        http().joinEvent(token, eventId, deviceId)
    override suspend fun publishManifest(token: String?, eventId: String, deviceId: String, manifest: DeviceManifest): Reply<Unit> =
        http().publishManifest(token, eventId, deviceId, manifest)
    override suspend fun leaveEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> =
        http().leaveEvent(token, eventId, deviceId)
    override suspend fun eventFiles(eventId: String): Reply<List<UnionAsset>> = http().eventFiles(eventId)
    override suspend fun deviceFiles(token: String?, deviceId: String): Reply<List<DeviceFile>> = http().deviceFiles(token, deviceId)
    override suspend fun putDeviceConfig(token: String?, deviceId: String, push: ApnsPushToken): Reply<Unit> =
        http().putDeviceConfig(token, deviceId, push)
}
