package app.snapsync.compose

import app.snapsync.ports.Backend
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.Clock
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Databases
import app.snapsync.ports.DevControls
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.Download
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.Files
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.Links
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
 * **One process's ports onto the device's systems** — what an iOS or Android root composes over, each resolved on first
 * use: the root hands its REAL adapters in as `lazy { … }` and composes over what its build's adapter set hands back —
 * the same bundle on a production build, and on a rig build the launch-time adapters', a mocked system's ports swapped
 * for its mock's faces (`docs/testing.md`, "Launch-time adapters"). Nothing is built until it is asked for, so a mocked
 * system's real adapter is never constructed.
 *
 * A process supplies only the ports it has: the upload extension has no screen, no lifecycle and no transfer session of
 * its own, and asking for a port its root did not supply fails naming it.
 */
class DevicePorts(
    clock: Lazy<Clock> = absent("clock"),
    crashReporter: Lazy<CrashReporter> = absent("crashReporter"),
    files: Lazy<Files> = absent("files"),
    databases: Lazy<Databases> = absent("databases"),
    preferences: Lazy<Preferences> = absent("preferences"),
    secureStore: Lazy<SecureStore> = absent("secureStore"),
    /** The platform's own stable device id, where it has one (none on iOS). */
    platformDeviceId: Lazy<PlatformDeviceId> = absent("platformDeviceId"),
    integrity: Lazy<DeviceIntegrity> = absent("integrity"),
    processInfo: Lazy<ProcessInfo> = absent("processInfo"),
    backend: Lazy<Backend> = absent("backend"),
    backgroundTime: Lazy<BackgroundTime> = absent("backgroundTime"),
    wake: Lazy<Wake> = absent("wake"),
    extensionRegistry: Lazy<ExtensionRegistry> = absent("extensionRegistry"),
    /** The app's photo library: its reads, its writes, its selection observer and its imports. */
    gallery: Lazy<Gallery> = absent("gallery"),
    /** The upload extension's photo library: reads and album adds only. */
    galleryReader: Lazy<GalleryReader> = absent("galleryReader"),
    photoAccess: Lazy<PhotoAccessStatusSource> = absent("photoAccess"),
    /** The app uploader's background transfer session. */
    appUpload: Lazy<Upload> = absent("appUpload"),
    /** The upload-job queue the upload cycle creates its jobs on. */
    cycleUpload: Lazy<Upload> = absent("cycleUpload"),
    download: Lazy<Download> = absent("download"),
    systemUi: Lazy<SystemUi> = absent("systemUi"),
    lifecycle: Lazy<Lifecycle> = absent("lifecycle"),
    links: Lazy<Links> = absent("links"),
    pushNotifications: Lazy<PushNotifications> = absent("pushNotifications"),
    ui: Lazy<Ui> = absent("ui"),
) {
    /** The constructor's lazies, so an adapter choice can hand the real ones through untouched. */
    val lazies: Lazies = Lazies(
        clock, crashReporter, files, databases, preferences, secureStore, platformDeviceId, integrity, processInfo,
        backend,
        backgroundTime, wake, extensionRegistry, gallery, galleryReader, photoAccess, appUpload, cycleUpload, download,
        systemUi, lifecycle, links, pushNotifications, ui,
    )

    val clock: Clock by clock
    val crashReporter: CrashReporter by crashReporter
    val files: Files by files
    val databases: Databases by databases
    val preferences: Preferences by preferences
    val secureStore: SecureStore by secureStore
    val platformDeviceId: PlatformDeviceId by platformDeviceId
    val integrity: DeviceIntegrity by integrity
    val processInfo: ProcessInfo by processInfo
    val backend: Backend by backend
    val backgroundTime: BackgroundTime by backgroundTime
    val wake: Wake by wake
    val extensionRegistry: ExtensionRegistry by extensionRegistry
    val gallery: Gallery by gallery
    val galleryReader: GalleryReader by galleryReader
    val photoAccess: PhotoAccessStatusSource by photoAccess
    val appUpload: Upload by appUpload
    val cycleUpload: Upload by cycleUpload
    val download: Download by download
    val systemUi: SystemUi by systemUi
    val lifecycle: Lifecycle by lifecycle
    val links: Links by links
    val pushNotifications: PushNotifications by pushNotifications
    val ui: Ui by ui

    /**
     * The app process's [AppPorts] over these ports — the one place an app root's ports become the composition's, so
     * an iOS and an Android root hand over the same set. What the bundle does not hold comes from the root: the
     * process's own [process] ports, and the two its build's adapter set decorates ([devControls] — inert on every
     * production build, the control channel's on a rig build — and [ui], the rig's decorator where it wraps it).
     *
     * Every field is read here, so a port the root did not supply fails naming it the moment the app composes.
     */
    fun appPorts(process: ProcessPorts, devControls: DevControls, ui: Ui): AppPorts = AppPorts(
        process = process,
        // This process's SQLite databases: the ledger (shared with an upload extension where one exists, every write
        // one guarded transaction) and the download store the app alone writes.
        databases = databases,
        preferences = preferences,
        // The protected small-value store — the device id, the attestation token.
        secureStore = secureStore,
        platformDeviceId = platformDeviceId,
        photoAccess = photoAccess,
        systemUi = systemUi,
        // Every photo-library read and write, the partial grant's selection observer (opened at host assembly only)
        // and the import of foreign photos, whose markers the core's handlers write.
        gallery = gallery,
        // The platform's background downloads — an event port the host zone listens to.
        download = download,
        // The backend: every need-shaped service is composed over it inside the core.
        backend = backend,
        integrity = integrity,
        // The app's uploader transport (a background `URLSession` on iOS; Android's only uploader).
        appUpload = appUpload,
        // The upload extension's registration record, on every platform: where it cannot exist (below iOS 26.1, and
        // on Android) the adapter answers `Unsupported` itself, so no root holds an `if` around it.
        extensionRegistry = extensionRegistry,
        devControls = devControls,
        // The entry ports: each registered by the host zone as this graph is composed, so a delivery in a background
        // wake finds its handler.
        pushNotifications = pushNotifications,
        lifecycle = lifecycle,
        links = links,
        ui = ui,
        backgroundTime = backgroundTime,
        // The operating system's scheduled wakes: the heartbeat the tail re-arms, and — by the host zone's `listen` —
        // its launch handler.
        wake = wake,
        processInfo = processInfo,
    )

    /** One lazy per port, as the constructor took them. */
    class Lazies(
        val clock: Lazy<Clock>,
        val crashReporter: Lazy<CrashReporter>,
        val files: Lazy<Files>,
        val databases: Lazy<Databases>,
        val preferences: Lazy<Preferences>,
        val secureStore: Lazy<SecureStore>,
        val platformDeviceId: Lazy<PlatformDeviceId>,
        val integrity: Lazy<DeviceIntegrity>,
        val processInfo: Lazy<ProcessInfo>,
        val backend: Lazy<Backend>,
        val backgroundTime: Lazy<BackgroundTime>,
        val wake: Lazy<Wake>,
        val extensionRegistry: Lazy<ExtensionRegistry>,
        val gallery: Lazy<Gallery>,
        val galleryReader: Lazy<GalleryReader>,
        val photoAccess: Lazy<PhotoAccessStatusSource>,
        val appUpload: Lazy<Upload>,
        val cycleUpload: Lazy<Upload>,
        val download: Lazy<Download>,
        val systemUi: Lazy<SystemUi>,
        val lifecycle: Lazy<Lifecycle>,
        val links: Lazy<Links>,
        val pushNotifications: Lazy<PushNotifications>,
        val ui: Lazy<Ui>,
    )
}

private fun <T> absent(port: String): Lazy<T> = lazy { error("this process's root supplies no $port port") }
