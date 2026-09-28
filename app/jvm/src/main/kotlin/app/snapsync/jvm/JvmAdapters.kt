package app.snapsync.jvm

import app.snapsync.feature.upload.AppUploadMechanism
import app.snapsync.ports.Backend
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.Clock
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Databases
import app.snapsync.ports.DevControls
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.Download
import app.snapsync.ports.ExtensionHost
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.Files
import app.snapsync.ports.Gallery
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
import co.touchlab.kermit.Logger

/**
 * **One launch's adapters** — every port the JVM root composes the app and its upload extension over, chosen by the
 * root's caller. A launch gets a fresh set ([JvmApp.relaunch] asks for one), built over whatever durable state the
 * caller keeps: the mocks' ([JvmMocks.adapters]), real JVM storage, or the real `api/` behind the backend port.
 *
 * Bundled by what they stand for: the [device] a process runs on, the operating system's [entries] into the app, and
 * the external [systems] the app talks to.
 */
class JvmAdapters(
    val build: JvmBuild,
    val device: JvmDevice,
    val entries: JvmEntries,
    val systems: JvmSystems,
)

/**
 * What a process finds on the device. Two processes live in one JVM here, as on the phone: the app's faces, and the
 * upload extension's where they differ ([extensionFiles] reaches only the shared area; [extensionCrashReporter] is a
 * channel nobody observes).
 */
class JvmDevice(
    val clock: Clock,
    val crashReporter: CrashReporter,
    val extensionCrashReporter: CrashReporter,
    val files: Files,
    val extensionFiles: Files,
    val databases: Databases,
    val preferences: Preferences,
    val secureStore: SecureStore,
    val integrity: DeviceIntegrity,
    val processInfo: ProcessInfo,
)

/** The operating system's entries into the app and its upload extension — the event ports. */
class JvmEntries(
    val lifecycle: Lifecycle,
    val links: Links,
    val pushNotifications: PushNotifications,
    val ui: Ui,
    val devControls: DevControls,
    val extensionHost: ExtensionHost,
)

/** The external systems the app talks to: the backend, the photo library, and the OS's background machinery. */
class JvmSystems(
    val backend: Backend,
    val backgroundTime: BackgroundTime,
    val wake: Wake,
    val extensionRegistry: ExtensionRegistry,
    /** The app process's photo library. */
    val gallery: Gallery,
    /** The upload cycle's own face of the library — the extension's reads. */
    val cycleGallery: Gallery,
    val photoAccess: PhotoAccessStatusSource,
    /** The app uploader's transfer session. */
    val appUpload: Upload,
    /** The OS-driven tier's upload-job queue, which the upload cycle creates its jobs on. */
    val cycleUpload: Upload,
    val download: Download,
    val systemUi: SystemUi,
    /** The app-driven uploader's mechanism — `OperatorDrivenUploads` where the operator invokes every cycle. */
    val appDrivenUpload: AppUploadMechanism,
)

/**
 * What the running build IS — the constants a device's bundle carries.
 *
 * [appVersion] is read per call by a backend port that takes it, and once — when the cycle is composed — by the
 * cycle, as on a device; it is a cell so an operator can play a member updating the app in place.
 */
class JvmBuild(
    /** The backend's device-facing base, carrying exactly one version prefix. */
    val host: String,
    val appVersion: app.snapsync.mock.DeclaredVersion,
    /** Where the build reports, or `null` for one that reports nowhere (a dev build keeps its bug report). */
    val dsn: String?,
    /** The build's App Store page — the update-required screen's one remedy. */
    val appStoreUrl: String?,
    /** The APNs environment the build's push tokens belong to. */
    val apnsEnvironment: String,
    /** Where the composed app's own log lines go. */
    val log: Logger,
)
