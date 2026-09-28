package app.snapsync.compose

import app.snapsync.ports.Backend
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.Clock
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Databases
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.Download
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.Files
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.Links
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.Preferences
import app.snapsync.ports.ProcessInfo
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SystemUi
import app.snapsync.ports.Ui
import app.snapsync.ports.Upload
import app.snapsync.ports.Wake

/**
 * **One process's ports onto the device's systems** — what an iOS root composes over, each resolved on first use: the
 * root hands its REAL adapters in as `lazy { … }` and composes over what its build's adapter set hands back — the same
 * bundle on a production build, and on a rig build the launch-time mix's, a mocked system's ports swapped for its
 * mock's faces (`docs/testing.md`, "The launch-time mock mix"). Nothing is built until it is asked for, so a mocked
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
    /** The constructor's lazies, so a mix can hand the real ones through untouched. */
    val lazies: Lazies = Lazies(
        clock, crashReporter, files, databases, preferences, secureStore, integrity, processInfo, backend,
        backgroundTime, wake, extensionRegistry, gallery, galleryReader, photoAccess, appUpload, cycleUpload, download,
        systemUi, lifecycle, links, pushNotifications, ui,
    )

    val clock: Clock by clock
    val crashReporter: CrashReporter by crashReporter
    val files: Files by files
    val databases: Databases by databases
    val preferences: Preferences by preferences
    val secureStore: SecureStore by secureStore
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

    /** One lazy per port, as the constructor took them. */
    class Lazies(
        val clock: Lazy<Clock>,
        val crashReporter: Lazy<CrashReporter>,
        val files: Lazy<Files>,
        val databases: Lazy<Databases>,
        val preferences: Lazy<Preferences>,
        val secureStore: Lazy<SecureStore>,
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
