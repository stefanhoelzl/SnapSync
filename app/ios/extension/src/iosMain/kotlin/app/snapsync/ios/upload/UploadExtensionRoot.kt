package app.snapsync.ios.upload

import app.snapsync.annotations.PlatformEntry
import app.snapsync.composition.extension.ExtensionDevicePorts
import app.snapsync.composition.extension.ExtensionGraph
import app.snapsync.composition.extension.ExtensionPorts
import app.snapsync.composition.extension.composeExtension
import app.snapsync.composition.process.ProcessPorts
import app.snapsync.config.IosBuildInfo
import app.snapsync.config.bakedUploadBase
import app.snapsync.crypto.IosCrypto
import app.snapsync.databases.IosDatabases
import app.snapsync.entry.NoEntryEvents
import app.snapsync.extension.IosExtensionHost
import app.snapsync.files.IosFiles
import app.snapsync.gallery.IosGalleryReader
import app.snapsync.http.KtorHttpTransport
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.keychain.platformSecureStore
import app.snapsync.logging.FileLogSink
import app.snapsync.logging.IosThreadLabel
import app.snapsync.logging.PublicNSLogSink
import app.snapsync.logging.extensionLogDestination
import app.snapsync.logging.neverBlockOnStdio
import app.snapsync.logging.removeStaleExtensionDocumentsLog
import app.snapsync.membership.darwinHttpClient
import app.snapsync.metrics.NoProcessMetrics
import app.snapsync.preferences.IosPreferences
import app.snapsync.sentry.SentryCrashReporter
import app.snapsync.time.SystemClock
import kotlinx.coroutines.Dispatchers

/**
 * The extension process's composition root — WIRING ONLY: it constructs this process's adapters
 * (the App-Group databases and files, the PhotoKit library reader and upload-job queue, the shared protected store,
 * the generic HTTP backend) and hands them as [ExtensionPorts] to its composition
 * [composeExtension] (`:domain:composition:extension`), which builds its graph and registers its handlers. The Swift
 * `@main` principal class calls [processRawValue] from its `process()` callback.
 *
 * Config is sourced fresh each cycle by the shared entry gate: the runtime event id from the shared
 * App-Group membership store (the data layer's `MembershipRepository` over [IosFiles]) combined
 * with the compile-time upload host
 * ([bakedUploadBase], the plist `uploadBase`). When no event has been joined yet (the
 * extension woke before setup), the cycle is skipped as a clean success — no job, no ledger write,
 * no crash.
 *
 * The composed graph and platform are process-lifetime singletons (the extension is the single
 * ledger record-writer on its tier); every store it holds opens on first use, never at composition.
 */
object UploadExtensionRoot {

    init {
        // First, before anything logs: a log line must never park a thread on an undrained stdout/stderr (see the KDoc).
        neverBlockOnStdio()
    }

    /**
     * Where this process's log goes: the SHARED App Group (`ext-debug.log`) rather than its own Documents, because the
     * app cannot read another bundle's Documents and the app is what assembles a diagnostic dump. The App Group is
     * not USB-pullable; the control channel reads it.
     */
    private val logDestination = extensionLogDestination()

    private val log = Log("UploadExtension")

    /**
     * This process's ports onto the device's systems, as its REAL adapters (`ExtensionDevicePorts`), each built on first use —
     * and [device], what its cycle composes over: these on a production build, and on a rig build the launch-time adapters',
     * where a mocked system's are its mock's (`extensionPorts()`, from `src/entries` or the rig's source;
     * `docs/testing.md`, "Launch-time adapters").
     */
    private val real: ExtensionDevicePorts = ExtensionDevicePorts(
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
        // The extension's gallery: reads and album adds only — no access request, no change token, no memo.
        galleryReader = lazy { IosGalleryReader() },
        // The upload-job queue, thin: the shared composition's upload service records terminal outcomes into the
        // ledger and acknowledges every presented job.
        //
        // WHICH adapter is chosen by the COMPILATION TARGET, not here. Every shipped binary is `iosArm64` and
        // binds the PhotoKit queue; `iosSimulatorArm64` binds a substitute, because on that host job creation
        // does not fail — it raises an uncaught ObjC exception inside PhotoKit and kills the process. This
        // root is unchanged either way: it names the need, and the target answers it.

        uploadQueue = lazy { uploadJobQueue(log) },
        // The same transport the app runs, over this process's own Darwin client.
        http = lazy { KtorHttpTransport(darwinHttpClient()) },
    )

    private val device: ExtensionDevicePorts = extensionPorts(real)

    /**
     * The operating system's invocations of this extension (`:adapter:ios:ext-safe`), and the port the composition
     * registers on: the adapter itself on a production build, or — only under `-Psnapsync.rig=true` — the control
     * channel's decorator over it, which runs a requested port contract in place of a cycle (`extensionHost()`, from
     * `src/entries` or the rig's source, chosen at build time).
     */
    private val host: IosExtensionHost = IosExtensionHost(log)

    /**
     * The extension, composed over its ports and nothing else (`composeExtension`, `:domain:composition:extension`):
     * the process first — its log writers, its ONE crash reporter started before any other wiring can fail, its banner —
     * then its graph (the read-only identity, the read-only suppression view, the credential that only drops a rejected
     * token) and the ONE registration on its entry port, at this process's start and before the operating system's
     * first `process()`. Building opens nothing: every store opens on the first invocation that reads it.
     *
     * No process metrics: MetricKit hands reports out roughly daily, and this process lives for one invocation.
     */
    internal val composed: ExtensionGraph = composeExtension(
        device.extensionPorts(
            process = ProcessPorts(
                crashReporter = device.crashReporter.value,
                processMetrics = NoProcessMetrics,
                // A public NSLog sink AND a file sink: NSLog is redacted as `<private>` on current iOS (dynamic format
                // strings are private), so the file is the reliable channel for reading the extension's logs on device.
                logSinks = listOf(PublicNSLogSink(), FileLogSink(logDestination.path)),
                files = device.files.value,
                clock = device.clock.value,
                crypto = device.crypto.value,
                threadLabel = IosThreadLabel,
                // Its own process: no control channel there hears it, and its log line is the diagnostic.
                entryEvents = NoEntryEvents,
                build = IosBuildInfo(),
            ),
            host = extensionHost(host),
        ),
        // The extension's lane: the pooled threads its detached work and the expiry grace have always run on; the cycle
        // itself runs on the operating system's thread, which `process()` blocks until it answers.
        lane = Dispatchers.Default,
        answerOn = null,
    )

    init {
        // Where this run's log is going — including whether it fell back to this bundle's own Documents, in which case
        // no dump will carry it (the sentence is the adapter's). Only this root knows it, so it is logged right after
        // the composition's boot banner.
        Log("UploadExtensionRoot").unlabelled.i("log destination", "line" of logDestination.bannerLine)
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
