package app.snapsync.launchadapters

import app.snapsync.compose.DevicePorts
import app.snapsync.mock.MockedSystem
import app.snapsync.mock.MockState

/**
 * [real] with each system [launch]'s adapter choice mocks swapped for its mock's face — [root]'s faces, one per port, built on
 * first use like the real adapter each stands in for. A system the adapter choice leaves real hands its real lazy through
 * untouched, so its adapter is built exactly when it would have been.
 */
internal fun chosenPorts(real: DevicePorts.Lazies, launch: LaunchAdapters.Chosen, root: AdapterProcess): DevicePorts {
    val device = launch.device
    val app = root == AdapterProcess.APP
    fun <T> pick(system: MockedSystem, real: Lazy<T>, mock: () -> T): Lazy<T> =
        if (launch.choice.isMocked(system)) lazy(mock) else real
    return DevicePorts(
        clock = pick(MockedSystem.CLOCK, real.clock) { device.clock.port() },
        // The extension reports to a channel nobody observes — the one the JVM root gives it too.
        crashReporter = pick(MockedSystem.CRASH_REPORTER, real.crashReporter) {
            if (app) device.crashReporter.port() else device.crashReporter.unobservedPort()
        },
        // The extension reaches only the shared area, as its sandbox does.
        files = pick(MockedSystem.FILES, real.files) { device.disk.port(privateArea = app) },
        databases = pick(MockedSystem.DATABASES, real.databases) { device.databases.port() },
        preferences = pick(MockedSystem.PREFERENCES, real.preferences) { device.preferences.port() },
        secureStore = pick(MockedSystem.KEYCHAIN, real.secureStore) { device.keychain.port() },
        // App Attest exists in the app and never in the extension.
        integrity = pick(MockedSystem.INTEGRITY, real.integrity) { device.enclave.port(available = app) },
        processInfo = pick(MockedSystem.PROCESS_INFO, real.processInfo) { device.processInfo.port() },
        backend = pick(MockedSystem.BACKEND, real.backend) { device.backend.port(device.declaredVersion) },
        backgroundTime = pick(MockedSystem.BACKGROUND_TIME, real.backgroundTime) { device.backgroundTime.port() },
        wake = pick(MockedSystem.WAKE, real.wake) { device.wakes.port() },
        extensionRegistry = pick(MockedSystem.EXTENSION_REGISTRY, real.extensionRegistry) { device.extensionRegistry.port() },
        gallery = pick(MockedSystem.LIBRARY, real.gallery) { device.library.port() },
        galleryReader = pick(MockedSystem.LIBRARY, real.galleryReader) { device.library.port() },
        photoAccess = pick(MockedSystem.LIBRARY, real.photoAccess) { device.library.photoAccess() },
        appUpload = pick(MockedSystem.UPLOAD_SESSION, real.appUpload) { device.uploadSession.port() },
        cycleUpload = pick(MockedSystem.UPLOAD_QUEUE, real.cycleUpload) { device.uploadQueue.port() },
        download = pick(MockedSystem.DOWNLOADS, real.download) { device.downloads.port() },
        systemUi = pick(MockedSystem.SYSTEM_UI, real.systemUi) { device.systemUi.port() },
        lifecycle = pick(MockedSystem.LIFECYCLE, real.lifecycle) { device.lifecycle.port() },
        links = pick(MockedSystem.LINKS, real.links) { device.links.port() },
        pushNotifications = pick(MockedSystem.PUSH, real.pushNotifications) { device.pushService.port() },
        ui = pick(MockedSystem.SCREEN, real.ui) { device.screen.port() },
    )
}
