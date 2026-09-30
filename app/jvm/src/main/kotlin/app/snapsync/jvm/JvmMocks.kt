package app.snapsync.jvm

import app.snapsync.http.HttpBackend
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.DeclaredVersion
import app.snapsync.mock.MockDevice
import app.snapsync.mock.UploadNetwork
import app.snapsync.model.PushEndpoint
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
import app.snapsync.model.UnionAsset
import app.snapsync.ports.Backend
import app.snapsync.ports.LogSink
import io.ktor.client.HttpClient

/**
 * **The device as mocks**, for the JVM root — the durable state a [JvmApp] over mocks keeps across a relaunch: the
 * common [MockDevice] (`docs/testing.md`, "Mocks"), plus the one launch's adapters over it ([adapters]).
 */
class JvmMocks(
    ownDeviceId: String = DEFAULT_DEVICE_ID,
    inviteLinkHints: InviteLinkHints = InviteLinkHints.Ignored,
    network: UploadNetwork? = null,
) : MockDevice(ownDeviceId, inviteLinkHints, network) {

    /**
     * One launch's adapters over these mocks. [attests] is whether the app process has App Attest (a simulator has
     * not); [backend] is the backend port — the mock's own face by default, or one reaching the real `api/`.
     */
    fun adapters(
        build: BuildInfoMock,
        attests: Boolean,
        backend: Backend = this.backend.port(build.declaredVersion),
        logSinks: List<LogSink> = emptyList(),
    ): JvmAdapters =
        JvmAdapters(
            build = build,
            device = JvmDevice(
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
                logSinks = logSinks,
            ),
            entries = JvmEntries(
                lifecycle = lifecycle.port(),
                links = links.port(),
                pushNotifications = pushService.port(),
                ui = screen.port(),
                devControls = devControls.port(),
                extensionHost = extensionHost.port(),
            ),
            systems = JvmSystems(
                backend = backend,
                backgroundTime = backgroundTime.port(),
                wake = wakes.port(),
                extensionRegistry = extensionRegistry.port(),
                gallery = library.port(),
                cycleGallery = library.port(),
                photoAccess = library.photoAccess(),
                appUpload = uploadSession.port(),
                cycleUpload = uploadQueue.port(),
                download = downloads.port(),
                systemUi = systemUi.port(),
            ),
        )

    companion object {
        /** The device id a mocked device carries unless told otherwise. */
        const val DEFAULT_DEVICE_ID: String = MockDevice.DEFAULT_DEVICE_ID
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
    override suspend fun publishManifest(
        token: String?,
        eventId: String,
        deviceId: String,
        manifest: DeviceManifest,
    ): Reply<Unit> = http().publishManifest(token, eventId, deviceId, manifest)
    override suspend fun leaveEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> =
        http().leaveEvent(token, eventId, deviceId)
    override suspend fun eventFiles(eventId: String): Reply<List<UnionAsset>> = http().eventFiles(eventId)
    override suspend fun deviceFiles(token: String?, deviceId: String): Reply<List<DeviceFile>> =
        http().deviceFiles(token, deviceId)
    override suspend fun putDeviceConfig(token: String?, deviceId: String, push: PushEndpoint): Reply<Unit> =
        http().putDeviceConfig(token, deviceId, push)
}
