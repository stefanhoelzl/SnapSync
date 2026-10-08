package app.snapsync.compose

import app.snapsync.ports.Backend
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.Clock
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Crypto
import app.snapsync.ports.Databases
import app.snapsync.ports.DevControls
import app.snapsync.ports.DeviceConditions
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.Download
import app.snapsync.ports.ExtensionHost
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.Files
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.Links
import app.snapsync.ports.NetworkMonitor
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.Preferences
import app.snapsync.ports.ProcessInfo
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SystemUi
import app.snapsync.ports.Ui
import app.snapsync.ports.Upload
import app.snapsync.ports.Wake

/**
 * **The app process's ports onto the device's systems** — what an iOS or Android app root composes over, each resolved
 * on first use: the root hands its REAL adapters in as `lazy { … }` and composes over what its build's adapter set
 * hands back — the same bundle on a production build, and on a rig build the launch-time adapters', a mocked system's
 * ports swapped for its mock's faces (`docs/testing.md`, "Launch-time adapters"). Nothing is built until it is asked
 * for, so a mocked system's real adapter is never constructed.
 *
 * Every port is required: a process's bundle names exactly the ports it has, so a port its root did not supply is a
 * compile error, never a runtime one. The upload extension has its own bundle ([ExtensionDevicePorts]).
 */
class AppDevicePorts(
    val clock: Lazy<Clock>,
    /** The platform's cryptographic primitives. */
    val crypto: Lazy<Crypto>,
    val crashReporter: Lazy<CrashReporter>,
    val files: Lazy<Files>,
    val databases: Lazy<Databases>,
    val preferences: Lazy<Preferences>,
    val secureStore: Lazy<SecureStore>,
    /** The platform's own stable device id, where it has one (none on iOS). */
    val platformDeviceId: Lazy<PlatformDeviceId>,
    val integrity: Lazy<DeviceIntegrity>,
    val processInfo: Lazy<ProcessInfo>,
    /** The device's network as the operating system reports it to this app. */
    val network: Lazy<NetworkMonitor>,
    /** The device's power, battery, thermal state and background allowance, read for a bug report. */
    val deviceConditions: Lazy<DeviceConditions>,
    val backend: Lazy<Backend>,
    val backgroundTime: Lazy<BackgroundTime>,
    val wake: Lazy<Wake>,
    val extensionRegistry: Lazy<ExtensionRegistry>,
    /** The app's photo library: its reads, its writes, its selection observer and its imports. */
    val gallery: Lazy<Gallery>,
    val photoAccess: Lazy<PhotoAccessStatusSource>,
    /** The app uploader's background transfer session. */
    val appUpload: Lazy<Upload>,
    val download: Lazy<Download>,
    val systemUi: Lazy<SystemUi>,
    val lifecycle: Lazy<Lifecycle>,
    val links: Lazy<Links>,
    val pushNotifications: Lazy<PushNotifications>,
    val ui: Lazy<Ui>,
) {
    /**
     * The app process's [AppPorts] over these ports — the one place an app root's ports become the composition's, so
     * an iOS and an Android root hand over the same set. What the bundle does not hold comes from the root: the
     * process's own [process] ports, and the two its build's adapter set decorates ([devControls] — inert on every
     * production build, the control channel's on a rig build — and [ui], the rig's decorator where it wraps it).
     */
    fun appPorts(process: ProcessPorts, devControls: DevControls, ui: Ui): AppPorts = AppPorts(
        process = process,
        // This process's SQLite databases: the ledger (shared with an upload extension where one exists, every write
        // one guarded transaction) and the download store the app alone writes.
        databases = databases.value,
        preferences = preferences.value,
        // The protected small-value store — the device id, the attestation token.
        secureStore = secureStore.value,
        platformDeviceId = platformDeviceId.value,
        photoAccess = photoAccess.value,
        systemUi = systemUi.value,
        // Every photo-library read and write, the partial grant's selection observer (opened at host assembly only)
        // and the import of foreign photos, whose markers the core's handlers write.
        gallery = gallery.value,
        // The platform's background downloads — an event port the host zone listens to.
        download = download.value,
        // The backend: every need-shaped service is composed over it inside the core.
        backend = backend.value,
        integrity = integrity.value,
        // The app's uploader transport (a background `URLSession` on iOS; Android's only uploader).
        appUpload = appUpload.value,
        // The upload extension's registration record, on every platform: where it cannot exist (below iOS 26.1, and
        // on Android) the adapter answers `Unsupported` itself, so no root holds an `if` around it.
        extensionRegistry = extensionRegistry.value,
        devControls = devControls,
        // The entry ports: each registered by the host zone as this graph is composed, so a delivery in a background
        // wake finds its handler.
        pushNotifications = pushNotifications.value,
        lifecycle = lifecycle.value,
        links = links.value,
        ui = ui,
        backgroundTime = backgroundTime.value,
        // The operating system's scheduled wakes: the heartbeat the tail re-arms, and — by the host zone's `listen` —
        // its launch handler.
        wake = wake.value,
        processInfo = processInfo.value,
        network = network.value,
        deviceConditions = deviceConditions.value,
    )
}

/**
 * **The upload extension's ports onto the device's systems** — [AppDevicePorts]' twin for the extension process, which
 * has no screen, no lifecycle and no transfer session of its own: it reads the library, creates jobs on the OS's
 * upload-job queue, and reaches the backend.
 */
class ExtensionDevicePorts(
    val clock: Lazy<Clock>,
    /** The platform's cryptographic primitives. */
    val crypto: Lazy<Crypto>,
    val crashReporter: Lazy<CrashReporter>,
    val files: Lazy<Files>,
    val databases: Lazy<Databases>,
    val preferences: Lazy<Preferences>,
    val secureStore: Lazy<SecureStore>,
    /** The platform's own stable device id, where it has one (none on iOS). */
    val platformDeviceId: Lazy<PlatformDeviceId>,
    /** The upload extension's photo library: reads and album adds only. */
    val galleryReader: Lazy<GalleryReader>,
    /** The upload-job queue the upload cycle creates its jobs on. */
    val cycleUpload: Lazy<Upload>,
    val backend: Lazy<Backend>,
) {
    /**
     * The extension process's [ExtensionPorts] over these ports, its own [process] ports and the operating system's
     * [host] of it — the one place an extension root's ports become the composition's.
     */
    fun extensionPorts(process: ProcessPorts, host: ExtensionHost): ExtensionPorts = ExtensionPorts(
        process = process,
        // The App-Group databases: the ledger, shared with the app (either process may open it read-write and migrate
        // it), and the app's download store, opened READ-ONLY as the echo-suppression view.
        databases = databases.value,
        preferences = preferences.value,
        secureStore = secureStore.value,
        platformDeviceId = platformDeviceId.value,
        gallery = galleryReader.value,
        upload = cycleUpload.value,
        backend = backend.value,
        host = host,
    )
}
