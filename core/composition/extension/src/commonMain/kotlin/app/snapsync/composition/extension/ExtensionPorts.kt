package app.snapsync.composition.extension

import app.snapsync.composition.process.ProcessPorts
import app.snapsync.ports.Clock
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Crypto
import app.snapsync.ports.Databases
import app.snapsync.ports.DeviceConditions
import app.snapsync.ports.ExtensionHost
import app.snapsync.ports.Files
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.HttpTransport
import app.snapsync.ports.NetworkMonitor
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.Port
import app.snapsync.ports.Preferences
import app.snapsync.ports.SecureStore

/**
 * **The upload extension's ports** — everything its composition consumes, and nothing but ports ([Port];
 * `docs/architecture.md`, "The composition layer"). The root constructs this process's adapters and supplies them
 * here; [composeExtension] starts the process first, then builds its [ExtensionGraph] over these ports.
 *
 * The extension has no screen, no lifecycle and no transfer session of its own: it reads the library, creates jobs on
 * the OS's upload-job queue, and reaches the backend — and it is invoked through [host] alone. It has no platform
 * device id either: only an identity that mints reads one, and the extension's never mints.
 */
class ExtensionPorts(
    /** The ports every process has exactly one of: crash reporter, log sinks, files, clock, build. */
    val process: ProcessPorts,
    /** The App-Group databases: the ledger (shared with the app) and the app's download store (read-only here). */
    val databases: Databases,
    /** Small shared preferences: the mobile-data choice the app's menu writes, read per job. */
    val preferences: Preferences,
    /** The shared protected store: the device id, the attestation token and the event keys the app wrote. */
    val secureStore: SecureStore,
    /** The extension's photo library: reads and album adds only — and the grant it reads, which admits its cycle. */
    val gallery: GalleryReader,
    /** The OS's upload-job queue the cycle creates its jobs on. */
    val uploadQueue: OsUploadQueue,
    /** HTTP: the backend's protocol is composed over it, behind a credential that only drops a rejected token. */
    val http: HttpTransport,
    /** The network as the platform reports it — read for the delivery context only: PhotoKit waits for one itself. */
    val network: NetworkMonitor,
    /** The device's power and thermal state as this process may read it — for the delivery context only. */
    val deviceConditions: DeviceConditions,
    /** The operating system's invocations of this extension — the one entry port, listened to at composition. */
    val host: ExtensionHost,
)

/**
 * **The upload extension's ports onto the device's systems** — the app's `AppDevicePorts`' twin for the extension
 * process, each built on first use: these are a root's REAL adapters, and on a rig build the launch-time adapters'
 * (`docs/testing.md`, "Launch-time adapters").
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
    /** The upload extension's photo library: reads and album adds only. */
    val galleryReader: Lazy<GalleryReader>,
    /** The OS's upload-job queue the upload cycle creates its jobs on. */
    val uploadQueue: Lazy<OsUploadQueue>,
    val http: Lazy<HttpTransport>,
    val network: Lazy<NetworkMonitor>,
    val deviceConditions: Lazy<DeviceConditions>,
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
        gallery = galleryReader.value,
        uploadQueue = uploadQueue.value,
        http = http.value,
        network = network.value,
        deviceConditions = deviceConditions.value,
        host = host,
    )
}
