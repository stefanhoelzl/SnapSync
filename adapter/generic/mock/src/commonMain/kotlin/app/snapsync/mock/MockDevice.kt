package app.snapsync.mock

import app.snapsync.model.FileArea
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.SecureSlots

/**
 * **The device as mocks** (`docs/testing.md`, "Mocks"): one mock per external system, each holding what that system
 * keeps and handing a process its port face and its operator face to whoever plays it. The JVM root keeps one as the
 * durable state a launch composes over (`JvmMocks`); a rig build of the iOS app keeps one for the systems its launch
 * adapters mock (`:test:launch-adapters`).
 *
 * [network] is what an OS upload crosses: by default the backend mock's own byte route, so a completed job's bytes land
 * in [backend]; a caller that puts the real `api/` behind the backend port passes a network that reaches it.
 */
open class MockDevice(
    /** This device's id, already in its Keychain — a device whose app has launched before. */
    val ownDeviceId: String = DEFAULT_DEVICE_ID,
    /** Whether this build honours an invite link's dev/test hints — a shipped build ignores them. */
    inviteLinkHints: InviteLinkHints = InviteLinkHints.Ignored,
    network: UploadNetwork? = null,
    /** Whether this operating system carries the OS-driven upload mechanism (iOS ≥26.1): the JVM does not. */
    osDrivenUpload: Boolean = false,
    /** Where the databases are files, or `null` for in-memory ones. */
    databaseDirectory: String? = null,
    /** Where a finished download's bytes are left, or `null` for this device's own [disk]. */
    temporaryFiles: TemporaryFiles? = null,
    /** Whether the upload-job queue takes a real library's resource handles (see [UploadQueueMock]). */
    acceptsAnyUploadHandle: Boolean = false,
) {
    val backend = BackendMock()
    val library = PhotoLibraryMock()
    val disk = FileSystemMock()
    val databases = DatabasesMock(directory = databaseDirectory)
    val preferences = PreferencesMock()
    val keychain = SecureStoreMock(mapOf(SecureSlots.DEVICE_ID to ownDeviceId))
    val enclave = DeviceIntegrityMock()
    val crashReporter = CrashReporterMock()
    val processInfo = ProcessInfoMock()
    val clock = ClockMock()
    val wakes = WakeMock()
    val backgroundTime = BackgroundTimeMock()
    val extensionRegistry = ExtensionRegistryMock(supported = osDrivenUpload)
    /** Where an OS-performed upload lands: the backend mock's byte route, unless the caller routes it elsewhere. */
    private val uploadNetwork = network ?: UploadNetwork { url, headers, _ -> backend.operator.receive(url, headers) }
    val uploadQueue = UploadQueueMock(uploadNetwork, acceptsAnyHandle = acceptsAnyUploadHandle)
    val uploadSession = UploadSessionMock(uploadNetwork)
    val downloads = DownloadSessionMock(temporaryFiles ?: TemporaryFiles.on(disk.port()))
    val lifecycle = LifecycleMock()
    val links = LinksMock()
    val pushService = PushServiceMock()
    val screen = ScreenMock()
    val devControls = DevControlsMock(inviteLinkHints)
    val extensionHost = ExtensionHostMock()
    val systemUi = SystemUiMock()

    /**
     * Delete the app, as a member does from the home screen: its files (both areas — the App Group goes with the last
     * app of its group), its databases and its user defaults are gone. What outlives an app is kept: the Keychain (so
     * the device id), the photo library and everything the backend holds. Reinstalling is this followed by a launch.
     */
    fun uninstallApp() {
        disk.operator.area(FileArea.SHARED).clear()
        disk.operator.area(FileArea.PRIVATE).clear()
        databases.operator.deleteAll()
        preferences.values.clear()
    }

    /** The marketing version the app's build declares to the backend mock — a cell an operator may change. */
    val declaredVersion: DeclaredVersion = DeclaredVersion(null)

    companion object {
        /** The device id a mocked device carries unless told otherwise. */
        const val DEFAULT_DEVICE_ID: String = "00000000-0000-4000-9000-0000000000a1"
    }
}
