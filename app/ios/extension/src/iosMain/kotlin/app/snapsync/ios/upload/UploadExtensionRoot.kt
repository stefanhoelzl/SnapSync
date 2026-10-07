package app.snapsync.ios.upload

import app.snapsync.compose.NoProcessMetrics
import app.snapsync.gallery.IosGalleryReader
import app.snapsync.compose.ComposedExtension
import app.snapsync.compose.ExtensionPorts
import app.snapsync.compose.snapSyncExtension
import app.snapsync.extension.IosExtensionHost
import app.snapsync.logging.IosEntryContext
import app.snapsync.model.PlatformEntry
import app.snapsync.preferences.IosPreferences
import app.snapsync.files.IosFiles
import app.snapsync.config.IosBuildInfo
import app.snapsync.config.iosBootLines
import app.snapsync.config.bakedUploadBase
import app.snapsync.identity.NoPlatformDeviceId
import app.snapsync.keychain.platformSecureStore
import app.snapsync.databases.IosDatabases
import app.snapsync.http.HttpBackend
import app.snapsync.membership.darwinHttpClient
import app.snapsync.logging.FileLogSink
import app.snapsync.logging.extensionLogDestination
import app.snapsync.logging.removeStaleExtensionDocumentsLog
import app.snapsync.sentry.SentryCrashReporter
import app.snapsync.compose.DevicePorts
import app.snapsync.compose.ProcessPorts
import app.snapsync.time.SystemClock
import app.snapsync.crypto.IosCrypto
import app.snapsync.logging.appMarketingVersion
import app.snapsync.logging.PublicNSLogSink
import app.snapsync.logging.neverBlockOnStdio
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger

/**
 * The extension process's composition root — WIRING ONLY: it constructs this process's adapters
 * (the App-Group databases and files, the PhotoKit library reader and upload-job queue, the shared protected store,
 * the generic HTTP backend) and hands them as [ExtensionPorts] to the SHARED composition
 * [snapSyncExtension] (`:domain` `compose/`), which builds the services and assembles the upload cycle. The Swift
 * `@main` principal class calls [processRawValue] from its `process()` callback.
 *
 * Config is sourced fresh each cycle by the shared entry gate: the runtime event id from the shared
 * App-Group config file ([ConfigService] over [IosFiles] — the file is the config's only home) combined
 * with the compile-time upload host
 * ([bakedUploadBase], the plist `uploadBase`). When no event has been joined yet (the
 * extension woke before setup), the cycle is skipped as a clean success — no job, no ledger write,
 * no crash.
 *
 * The composed cycle and platform are process-lifetime singletons (the extension is the single
 * ledger record-writer on its tier); the engine, which depends on config, is built per cycle
 * inside `uploadCore`.
 */
object UploadExtensionRoot {

    init {
        // First, before anything logs: a log line must never park a thread on an undrained stdout/stderr (see the KDoc).
        neverBlockOnStdio()
    }

    /**
     * Where this process's log goes: the SHARED App Group (`ext-debug.log`) rather than its own Documents, because the
     * app cannot read another bundle's Documents and the app is what assembles a diagnostic dump (capability
     * `privacy-security`). The App Group is not USB-pullable; the control channel reads it.
     */
    private val logDestination = extensionLogDestination()

    private val log = Logger.withTag("UploadExtension")

    /**
     * This process's ports onto the device's systems, as its REAL adapters (`DevicePorts`), each built on first use —
     * and [device], what its cycle composes over: these on a production build, and on a rig build the launch-time adapters',
     * where a mocked system's are its mock's (`extensionPorts()`, from `src/entries` or the rig's source;
     * `docs/testing.md`, "Launch-time adapters").
     */
    private val real: DevicePorts = DevicePorts(
        clock = lazyOf(SystemClock),
        // CryptoKit's AES-GCM, CommonCrypto's HMAC and the Security framework's generator.
        crypto = lazy { IosCrypto() },
        crashReporter = lazy { SentryCrashReporter() },
        files = lazy { IosFiles() },
        // This process's SQLite databases, in the App-Group container. The services open them on first use, never at
        // construction: building the composition opens no database (`docs/architecture.md`).
        databases = lazy { IosDatabases() },
        preferences = lazy { IosPreferences() },
        // This process's protected small-value store, every item addressed by its slot (chosen by compilation target).
        secureStore = lazy { platformSecureStore() },
        // iOS offers no stable platform id an app may read; this process only ever reads the id the app minted.
        platformDeviceId = lazyOf(NoPlatformDeviceId()),
        // The extension's gallery: reads and album adds only — no access request, no change token, no memo.
        galleryReader = lazy { IosGalleryReader() },
        // The upload-job queue, thin: the shared composition's upload service records terminal outcomes into the
        // ledger and acknowledges every presented job.
        //
        // WHICH adapter is chosen by the COMPILATION TARGET, not here (capability `background-upload`,
        // "The upload-job subsystem binding is fixed by the compilation target"). Every shipped binary is
        // `iosArm64` and binds the PhotoKit queue; `iosSimulatorArm64` binds a substitute, because on that
        // host job creation does not fail — it raises an uncaught ObjC exception inside PhotoKit and kills
        // the process. This root is unchanged either way: it names the need, and the target answers it.
        cycleUpload = lazy { uploadJobQueue(log) },
        // The same `HttpBackend` the app runs, over this process's own Darwin client.
        backend = lazy { HttpBackend(darwinHttpClient(), bakedUploadBase(), appMarketingVersion()) },
    )

    private val device: DevicePorts = extensionPorts(real)

    /**
     * The operating system's invocations of this extension (`:adapter:ios:ext-safe`), and the port the composition
     * registers on: the adapter itself on a production build, or — only under `-Psnapsync.rig=true` — the control
     * channel's decorator over it, which runs a requested port contract in place of a cycle (`extensionHost()`, from
     * `src/entries` or the rig's source, chosen at build time).
     */
    private val host: IosExtensionHost = IosExtensionHost(log)

    /**
     * The extension, composed over its ports and nothing else (`snapSyncExtension`, `:domain` `compose/`): the process
     * first — its log writers and boot banner, its ONE crash reporter started before any other wiring can fail — then
     * its services (the READ_ONLY identity, the read-only suppression view, the credential that only drops a rejected
     * token) and the ONE registration on its entry port, at this process's start and before the operating system's
     * first `process()`. Registering builds nothing: the cycle is assembled on the first invocation.
     *
     * No process metrics: MetricKit hands reports out roughly daily, and this process lives for one invocation.
     */
    internal val composed: ComposedExtension = snapSyncExtension(
        ExtensionPorts(
            process = ProcessPorts(
                crashReporter = device.crashReporter,
                processMetrics = NoProcessMetrics,
                // A public NSLog sink AND a file sink: NSLog is redacted as `<private>` on current iOS (dynamic format
                // strings are private), so the file is the reliable channel for reading the extension's logs on device.
                logSinks = listOf(PublicNSLogSink(), FileLogSink(logDestination.path)),
                files = device.files,
                clock = device.clock,
                crypto = device.crypto,
                entryContext = IosEntryContext,
                build = IosBuildInfo(
                    // This process exists only where the OS carries the OS-driven mechanism (iOS ≥26.1).
                    osSupportsOsDrivenUpload = true,
                    // Where this run's log is going — including whether it fell back to this bundle's own Documents, in
                    // which case no dump will carry it (the sentence is the adapter's). The upload base the banner
                    // names matters more here than in the app: this process IS the upload path.
                    bootLines = iosBootLines("extension", listOf(logDestination.bannerLine)),
                ),
            ),
            // The App-Group databases: the ledger, shared with the app (either process may open it read-write and
            // migrate it), and the app's download store, opened READ-ONLY as the echo-suppression view.
            databases = device.databases,
            preferences = device.preferences,
            secureStore = device.secureStore,
            platformDeviceId = device.platformDeviceId,
            gallery = device.galleryReader,
            upload = device.cycleUpload,
            backend = device.backend,
            host = extensionHost(host),
        ),
    )

    init {
        // The pre-relocation file at the old path would otherwise keep answering pulls with frozen
        // content forever. Idempotent, and a no-op while the sink is itself falling back there.
        removeStaleExtensionDocumentsLog(logDestination)
    }

    /**
     * `process()`, forwarded from the Swift principal class: one invocation, answered as the iOS 26.1
     * `PHBackgroundResourceUploadProcessingResult` **raw value** the Swift side constructs with `init?(rawValue:)`
     * (the system type is Swift-only). The decision — which case each result means — is the adapter's tested mapping.
     */
    @PlatformEntry
    fun processRawValue(): Int = host.deliverProcess()

    /** `notifyTermination()`, forwarded from the Swift principal class: the end of an invocation, only recorded. */
    @PlatformEntry
    fun onTerminate() = host.deliverTerminate()
}
