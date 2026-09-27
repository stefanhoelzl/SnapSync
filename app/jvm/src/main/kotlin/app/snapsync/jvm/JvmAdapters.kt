package app.snapsync.jvm

import app.snapsync.feature.upload.AppUploadMechanism
import app.snapsync.feature.upload.WalkOutcome
import app.snapsync.model.CycleResult
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
 * Two processes live in one JVM here, as on the phone: the app's faces, and the upload extension's where they differ
 * ([extensionFiles] reaches only the shared area; [extensionCrashReporter] is a channel nobody observes).
 */
class JvmAdapters(
    val build: JvmBuild,
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
    val backend: Backend,
    val backgroundTime: BackgroundTime,
    val wake: Wake,
    val extensionRegistry: ExtensionRegistry,
    val lifecycle: Lifecycle,
    val links: Links,
    val pushNotifications: PushNotifications,
    val ui: Ui,
    val devControls: DevControls,
    val extensionHost: ExtensionHost,
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
    /** The app-driven uploader's mechanism — [OperatorDrivenUploads] where the operator invokes every cycle. */
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

/**
 * The app-driven uploader of a JVM where **the operator is the engine**: its units do nothing, so nothing uploads on
 * its own, and a cycle runs when the operator invokes the upload extension. The tail runner still reaches it from every
 * wake the operator delivers, as on a device.
 *
 * The JVM root's one stated deviation from the phone, carried over from the world it replaces: the app process has
 * no transfer session of its own that creates jobs, so every upload goes through the cycle over [JvmAdapters.cycleUpload].
 */
object OperatorDrivenUploads : AppUploadMechanism {
    override suspend fun topUp(stopRequested: () -> Boolean): CycleResult = CycleResult.COMPLETED

    override suspend fun walkAndPublish(stopRequested: () -> Boolean): WalkOutcome =
        WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = false)

    override suspend fun cancelTransfers() = Unit
}
