package app.snapsync.launchadapters

import app.snapsync.compose.AppDevicePorts
import app.snapsync.compose.ExtensionDevicePorts
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem

/**
 * [real] with each system [choice] mocks swapped for [device]'s mock of it — the app's faces, one per port, built on
 * first use like the real adapter each stands in for. A system the choice leaves real hands its real lazy through
 * untouched, so its adapter is built exactly when it would have been.
 *
 * A launch read from the adapters file composes through this ([LaunchAdapters.Chosen.ports]); so does a build whose
 * choice is fixed rather than read — the Android rig build, whose platform has no real adapter for most systems yet.
 */
fun chosenAppPorts(real: AppDevicePorts, choice: AdapterChoice, device: MockDevice): AppDevicePorts {
    fun <T> pick(system: MockedSystem, real: Lazy<T>, mock: () -> T): Lazy<T> =
        if (choice.isMocked(system)) lazy(mock) else real
    return AppDevicePorts(
        clock = pick(MockedSystem.CLOCK, real.clock) { device.clock.port() },
        // No system of its own: the primitives keep no state, so the platform's always stand.
        crypto = real.crypto,
        crashReporter = pick(MockedSystem.CRASH_REPORTER, real.crashReporter) { device.crashReporter.port() },
        files = pick(MockedSystem.FILES, real.files) { device.disk.port(privateArea = true) },
        databases = pick(MockedSystem.DATABASES, real.databases) { device.databases.port() },
        preferences = pick(MockedSystem.PREFERENCES, real.preferences) { device.preferences.port() },
        secureStore = pick(MockedSystem.KEYCHAIN, real.secureStore) { device.keychain.port() },
        // No system of its own: the platform's id is read-only and never written, so the real one always stands.
        platformDeviceId = real.platformDeviceId,
        integrity = pick(MockedSystem.INTEGRITY, real.integrity) { device.enclave.port(available = true) },
        processInfo = pick(MockedSystem.PROCESS_INFO, real.processInfo) { device.processInfo.port() },
        network = pick(MockedSystem.NETWORK, real.network) { device.connectivity.port() },
        deviceConditions = pick(
            MockedSystem.DEVICE_CONDITIONS,
            real.deviceConditions,
        ) { device.deviceConditions.port() },
        backend = pick(MockedSystem.BACKEND, real.backend) { device.backend.port(device.declaredVersion) },
        backgroundTime = pick(MockedSystem.BACKGROUND_TIME, real.backgroundTime) { device.backgroundTime.port() },
        wake = pick(MockedSystem.WAKE, real.wake) { device.wakes.port() },
        extensionRegistry = pick(
            MockedSystem.EXTENSION_REGISTRY,
            real.extensionRegistry,
        ) { device.extensionRegistry.port() },
        gallery = pick(MockedSystem.LIBRARY, real.gallery) { device.library.port() },
        photoAccess = pick(MockedSystem.LIBRARY, real.photoAccess) { device.library.photoAccess() },
        appUpload = pick(MockedSystem.UPLOAD_SESSION, real.appUpload) { device.uploadSession.port() },
        download = pick(MockedSystem.DOWNLOADS, real.download) { device.downloads.port() },
        systemUi = pick(MockedSystem.SYSTEM_UI, real.systemUi) { device.systemUi.port() },
        lifecycle = pick(MockedSystem.LIFECYCLE, real.lifecycle) { device.lifecycle.port() },
        links = pick(MockedSystem.LINKS, real.links) { device.links.port() },
        pushNotifications = pick(MockedSystem.PUSH, real.pushNotifications) { device.pushService.port() },
        ui = pick(MockedSystem.SCREEN, real.ui) { device.screen.port() },
    )
}

/**
 * [real] with each system [choice] mocks swapped for [device]'s mock of it — the upload extension's faces, in its own
 * process and inside the app alike: it reaches only the shared files and reports to a channel nobody observes.
 */
fun chosenExtensionPorts(real: ExtensionDevicePorts, choice: AdapterChoice, device: MockDevice): ExtensionDevicePorts {
    fun <T> pick(system: MockedSystem, real: Lazy<T>, mock: () -> T): Lazy<T> =
        if (choice.isMocked(system)) lazy(mock) else real
    return ExtensionDevicePorts(
        clock = pick(MockedSystem.CLOCK, real.clock) { device.clock.port() },
        crypto = real.crypto,
        // The extension reports to a channel nobody observes — the one the JVM root gives it too.
        crashReporter = pick(MockedSystem.CRASH_REPORTER, real.crashReporter) { device.crashReporter.unobservedPort() },
        // The extension reaches only the shared area, as its sandbox does.
        files = pick(MockedSystem.FILES, real.files) { device.disk.port(privateArea = false) },
        databases = pick(MockedSystem.DATABASES, real.databases) { device.databases.port() },
        preferences = pick(MockedSystem.PREFERENCES, real.preferences) { device.preferences.port() },
        secureStore = pick(MockedSystem.KEYCHAIN, real.secureStore) { device.keychain.port() },
        platformDeviceId = real.platformDeviceId,
        galleryReader = pick(MockedSystem.LIBRARY, real.galleryReader) { device.library.port() },
        cycleUpload = pick(MockedSystem.UPLOAD_QUEUE, real.cycleUpload) { device.uploadQueue.port() },
        backend = pick(MockedSystem.BACKEND, real.backend) { device.backend.port(device.declaredVersion) },
    )
}
