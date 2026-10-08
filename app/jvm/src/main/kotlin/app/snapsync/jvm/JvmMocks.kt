package app.snapsync.jvm

import app.snapsync.crypto.JcaCrypto
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.MockDevice
import app.snapsync.mock.UploadNetwork
import app.snapsync.model.InviteLinkHints
import app.snapsync.ports.Backend
import app.snapsync.ports.LogSink

/**
 * **The device as mocks**, for the JVM root — the durable state a [JvmApp] over mocks keeps across a relaunch: the
 * common [MockDevice] (`docs/testing.md`, "Mocks"), plus the one launch's adapters over it ([adapters]).
 */
class JvmMocks(
    ownDeviceId: String = DEFAULT_DEVICE_ID,
    inviteLinkHints: InviteLinkHints,
    network: UploadNetwork?,
) : MockDevice(ownDeviceId, inviteLinkHints, network) {

    /**
     * One launch's adapters over these mocks. [attests] is whether the app process has App Attest (a simulator has
     * not); [backend] is the backend port — the mock's own face, or one reaching the real `api/`; [logSinks] where the
     * app process's log lines go (see [JvmDevice.logSinks]).
     */
    fun adapters(
        build: BuildInfoMock,
        attests: Boolean,
        backend: Backend,
        logSinks: List<LogSink>,
    ): JvmAdapters =
        JvmAdapters(
            build = build,
            device = JvmDevice(
                crypto = eventKeys.recording(JcaCrypto()),
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
                network = connectivity.port(),
                deviceConditions = deviceConditions.port(),
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
                backend = eventKeys.recording(backend),
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
