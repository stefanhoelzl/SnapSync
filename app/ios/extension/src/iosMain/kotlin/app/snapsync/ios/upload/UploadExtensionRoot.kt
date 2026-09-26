package app.snapsync.ios.upload

import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.DeviceIdentity
import app.snapsync.compose.UploaderProcess
import app.snapsync.model.SelectionScope
import app.snapsync.compose.AlbumLookupFailure
import app.snapsync.gallery.IosGalleryReader
import app.snapsync.services.trust.CachedAttestStore
import app.snapsync.compose.UploadPorts
import app.snapsync.compose.uploadCore
import app.snapsync.compose.extensionEntries
import app.snapsync.ports.ExtensionEntries
import app.snapsync.logging.IosEntryContext
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.model.PlatformEntry
import app.snapsync.preferences.IosPreferences
import app.snapsync.services.album.AlbumMapService
import app.snapsync.files.IosFiles
import app.snapsync.ports.Files
import app.snapsync.services.config.ConfigService
import app.snapsync.config.bakedUploadBase
import app.snapsync.identity.NoPlatformDeviceId
import app.snapsync.keychain.platformSecureStore
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.ports.SecureStore
import app.snapsync.services.identity.AttestState
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.ports.SuppressionSource
import app.snapsync.databases.IosDatabases
import app.snapsync.ports.Databases
import app.snapsync.services.downloads.SuppressionService
import app.snapsync.ports.AlbumManager
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.ports.UploadDiscovery
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.gallery.GalleryDiscovery
import app.snapsync.compose.extensionBackend
import app.snapsync.http.HttpBackend
import app.snapsync.ports.Upload
import app.snapsync.model.CycleResult
import app.snapsync.ports.processingResultRawValue
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.ports.LedgerStore
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.membership.darwinHttpClient
import app.snapsync.logging.FileLogSink
import app.snapsync.logging.extensionLogDestination
import app.snapsync.logging.removeStaleExtensionDocumentsLog
import app.snapsync.logging.SentryCrashReporter
import app.snapsync.config.bakedSentryDsn
import app.snapsync.compose.ProcessPorts
import app.snapsync.compose.ProcessServices
import app.snapsync.compose.snapSyncProcess
import app.snapsync.ports.ProcessMetrics
import app.snapsync.time.SystemClock
import app.snapsync.logging.appBuildVersion
import app.snapsync.logging.appMarketingVersion
import app.snapsync.logging.PublicNSLogSink
import app.snapsync.logging.neverBlockOnStdio
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * The extension process's composition root — WIRING ONLY: it constructs this process's adapters
 * (the App-Group ledger store, the PhotoKit platform + discovery, the file-backed config reader,
 * the Keychain attest reader, the generic HTTP clients) and hands them as [UploadPorts] to the SHARED composition
 * [uploadCore] (`:domain` `compose/`), which assembles the [UploadCycle]. The Swift `@main`
 * principal class calls [process] from its `process()` callback.
 *
 * Config is sourced fresh each cycle by the shared entry gate: the runtime event id from the shared
 * App-Group config file ([ConfigService] over [IosFiles] — writes are file-only since the migration
 * finale ended the 11a Keychain write-through; the read keeps the legacy-Keychain migration
 * fallback until the post-ship Stage-2 change, so this extension can be the process that migrates
 * a pre-file device on the OS's first post-update invocation) combined with the compile-time upload host
 * ([bakedUploadBase], the plist `uploadBase`). When no event has been joined yet (the
 * extension woke before setup), the cycle is skipped as a clean success — no job, no ledger write,
 * no crash.
 *
 * The composed cycle and platform are process-lifetime singletons (the extension is the single
 * ledger record-writer on its tier); the engine, which depends on config, is built per cycle
 * inside `uploadCore`.
 */
object UploadExtensionRoot : ExtensionEntries by extensionRootEntries() {

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

    /**
     * This process's per-process services (`snapSyncProcess`, every root's first act): its log writers and boot
     * banner, its ONE crash reporter — started here before any other wiring can fail — its files, clock and
     * entry-point seam. No process metrics: MetricKit hands reports out roughly daily, and this process lives for one
     * invocation.
     */
    private val process: ProcessServices = snapSyncProcess(
        ProcessPorts(
            crashReporter = SentryCrashReporter(),
            processMetrics = ProcessMetrics.None,
            // A public NSLog sink AND a file sink: NSLog is redacted as `<private>` on current iOS (dynamic format
            // strings are private), so the file is the reliable channel for reading the extension's logs on device.
            logSinks = listOf(PublicNSLogSink(), FileLogSink(logDestination.path)),
            files = IosFiles(),
            clock = SystemClock,
            entryContext = IosEntryContext,
            dsn = bakedSentryDsn(),
            bootLines = listOf(
                // The extension is a separate, short-lived process; name it + the build version so its file is
                // unambiguous (capability `privacy-security`, D5).
                "=== extension process start build=${appBuildVersion()} ===",
                // Where this run's log is going — including whether it fell back to this bundle's own Documents, in
                // which case no dump will carry it (the sentence is the adapter's).
                logDestination.bannerLine,
                // The BAKED backend this build uploads to — the same diagnostic the app emits, and it matters more
                // here: this process IS the upload path, and pointing a build at a different backend without a
                // device reset leaves the ledger claiming everything is already COMPLETED, so the cycle enumerates
                // and enqueues nothing with no error anywhere.
                "[boot] upload base = ${bakedUploadBase()}",
            ),
            ownsGlobalLogger = true,
        ),
    )

    init {
        // The pre-relocation file at the old path would otherwise keep answering pulls with frozen
        // content forever. Idempotent, and a no-op while the sink is itself falling back there.
        removeStaleExtensionDocumentsLog(logDestination)
    }

    private val log = Logger.withTag("UploadExtension")

    // This process's SQLite databases, in the App-Group container. The services below open them on first use,
    // never at construction: building the composition opens no database (`docs/architecture.md`).
    private val databases: Databases by lazy { IosDatabases() }

    // The ledger: shared with the app; either process may open it read-write and migrate it.
    private val ledgerStore: LedgerStore by lazy { LedgerService(databases) }
    // The extension's gallery: reads and album adds only — no access request, no change token, no memo.
    private val gallery: GalleryReader by lazy { IosGalleryReader() }
    private val discovery: UploadDiscovery by lazy { GalleryDiscovery(gallery) }
    private val platform: Upload by lazy {
        // The upload-job queue, thin: the shared composition's upload service records terminal outcomes into the
        // ledger and acknowledges every presented job.
        //
        // WHICH adapter is chosen by the COMPILATION TARGET, not here (capability `background-upload`,
        // "The upload-job subsystem binding is fixed by the compilation target"). Every shipped binary is
        // `iosArm64` and binds the PhotoKit queue; `iosSimulatorArm64` binds a substitute, because on that
        // host job creation does not fail — it raises an uncaught ObjC exception inside PhotoKit and kills
        // the process. This root is unchanged either way: it names the need, and the target answers it.
        uploadJobQueue(log)
    }

    // The app-written download store, opened READ-ONLY through the NARROWED SuppressionSource type
    // (capability `receiving-photos`): only which downloaded-then-imported assets must not be re-uploaded,
    // never the full DownloadStore surface. It never creates or migrates the store — the app does — so an
    // extension that runs before the updated app has pauses its cycle instead (`SuppressionService`).
    private val suppression: SuppressionSource by lazy { SuppressionService(databases) }
    private val files: Files get() = process.files
    private val configSource: ConfigService by lazy { ConfigService(files) }

    // Event album (capability `event-album`): the coordinator over the shared leave-surviving map and the
    // gallery's album operations. The extension only ever ADDS completed uploads (the app is the sole creator).
    // Hoisted because the selection policy also reads it (denylisted-album membership).
    private val albumManager: AlbumManager by lazy { GalleryAlbums(gallery) }
    private val albumCoordinator: AlbumCoordinator by lazy {
        AlbumCoordinator(albumManager, AlbumMapService(IosPreferences(), secureStore))
    }

    // The stable per-install device id (shared Keychain access group, addressed by name): the
    // `/files/devices/<deviceId>/` byte-store partition the provider writes to, and the per-event
    // device-manifest key. Resolved once for the process lifetime.
    //
    // READ_ONLY — this process neither mints nor adopts (capability `photo-sharing`). It cannot tell
    // "no identity yet" from "the app's identity is not reachable from here", and guessing is what gave
    // this device two identities: the extension uploaded under one while the app reconciled under the
    // other, so the app re-imported every photo the device itself had uploaded. Absence raises
    // `DeviceIdentityAbsent` and the cycle gate skips, exactly as it does for an unreadable Keychain.
    private val deviceIdentity: DeviceIdentity by lazy {
        PersistedDeviceIdentity(DeviceIdentityRole.READ_ONLY, secureStore, NoPlatformDeviceId())
    }

    // This process's protected small-value store, every item addressed by its slot (chosen by compilation target).
    private val secureStore: SecureStore by lazy { platformSecureStore() }

    // One shared Darwin (NSURLSession) HTTP client for both in-cycle network calls (the reconcile
    // listing GET and the device.json PUT) — a single client avoids running two NSURLSession-backed
    // engines under the same `runBlocking`.
    /**
     * The device token (capability `privacy-security`), read from the **shared Keychain** — the app put
     * it there.
     *
     * The extension is a strict READER. It never attests and never renews, because it *cannot*:
     * `DCAppAttestService.isSupported` is `false` in an app extension and `true` in the app — measured on
     * this device, in this very process, in a healthy cycle that uploaded a photo one second later.
     *
     * So an expired token is simply sent as-is. The upload `401`s, the engine retries forever (it is
     * error-agnostic) and re-mints the request from the provider each attempt — so once the APP next wakes
     * and renews, the very same resources upload with no special-casing anywhere in this file.
     *
     * Absence: null means "no usable token", collapsing absent and unreadable — see below for why
     * that is kept and what it now costs.
     *
     * Non-throwing: the Keychain is unreadable before the first unlock since boot, and this is called on a
     * background wake, which is exactly when that happens. A null token is a `401`, which is retryable; a
     * thrown error here would take down the cycle.
     *
     * The collapse is kept — but it is **no longer silent**, and the reason is the law (spec
     * `docs/architecture.md`, "Absence is never silent"). The justification above covers
     * `errSecInteractionNotAllowed` (-25308, locked device, retryable). The same `runCatching`
     * also absorbs `errSecMissingEntitlement` (-34018), which is **permanent**, not retryable, and
     * produced the "dead in the water" mis-signing incident of 2026-07-21 — a cause with a materially
     * different consequence that the stated reason never covered. `KeychainRead` deliberately carries
     * the status apart from absence (`Unavailable(status)`, "never mistaken for absence") and
     * `readExisting` throws rather than return null, so discarding it here without a word threw away
     * the one fact that distinguishes the two. Whether the cycle should *stop* on -34018 instead of
     * 401-looping is a separate question, deliberately left open.
     *
     * Held in memory between re-reads ([CachedAttestStore]) — every request and every upload request built
     * reads it, and a Keychain read each time was a measurable cost. Re-read at every `process()` invocation
     * (the app renews into the shared item) and on every rejection (the compare-and-clear reads the Keychain).
     */
    internal val attestStore: CachedAttestStore by lazy { CachedAttestStore(AttestState(secureStore)) }

    private fun attestToken(): String? = runCatchingCancellable { attestStore.token() }
        .onFailure { log.w(it) { "attest token unreadable — proceeding unauthenticated (expect 401)" } }
        .getOrNull()

    /**
     * The extension's backend services (`compose/`'s [extensionBackend]): the same `HttpBackend` the app runs over
     * its own Darwin client, behind a credential that only DROPS a token the backend rejected. The extension cannot
     * attest, so it cannot recover on its own — but dropping a rejected token is what makes the app re-mint at its
     * next wake: `isStale(null)` is true, while a rejected-but-unexpired token would have looked perfectly fine
     * forever. Only if the shared item still holds the token that was refused: the app may have renewed it meanwhile.
     */
    private val backend by lazy {
        extensionBackend(HttpBackend(darwinHttpClient(), bakedUploadBase(), appMarketingVersion()), attestStore, deviceIdentity)
    }

    // The extension's process scope, handed to the shared composition per its contract (`module-
    // architecture`, "the composition functions SHALL receive a CoroutineScope"). The upload subset
    // consumes no scope until step 8 installs the port-state-transition subscriptions in compose/;
    // this extension's own execution model stays the synchronous per-`process()` `runBlocking` below.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The cycle — assembled by the SHARED composition `uploadCore` (`docs/architecture.md`, "One
     * shared composition"): this root supplies only its ports and platform reads (the Keychain
     * three-state `ConfigReader`, the identity resolve, the compile-time bundle host, the PhotoKit
     * platform, the App-Group stores, and the generic HTTP adapters). The entry-gate translation,
     * the reconciler, the device-manifest producer, and the engine wiring live in `uploadCore` —
     * identical for the app-driven tier and the world harness, so this tier cannot carry cycle
     * wiring another tier lacks. Long-lived (one per process): the cycle re-reads the membership
     * per `run()`, so nothing here is per-invocation.
     */
    internal val cycle: UploadCycle by lazy { uploadCore(scope, process, ports) }

    /** Everything the cycle is built over — held so the inbound port's implementation reads the same ledger and log. */
    internal val ports: UploadPorts by lazy {
            UploadPorts(
                appVersion = appMarketingVersion(),
                // This process's own grant read (capability `background-upload`, "The extension withholds its
                // cycle without a full grant"): a registration made under a full grant survives a downgrade,
                // and a cycle here has no selection snapshot to scope to. Measured (SE2, iOS 26.6, 2026-09-21): the
                // OS invoked a surviving registration under `.limited` 4 s after a photo joined the selection, so
                // this gate is what stops it (changes/archive/2026-09-22-both-uploaders-active).
                process = UploaderProcess.Extension(PhotoGrantRead { gallery.access() }),
                // Unrestricted, stated: the extension never reads the library under a partial grant — the OS
                // does invoke a surviving registration there, but its admission withholds before any read.
                selectionScope = { SelectionScope.Unrestricted },
                config = configSource,
                // The lazy caches the first success; a failure throws `KeychainUnavailable` and is
                // retried next cycle — the gate's probe puts it on the unreadable side of the roll-up.
                deviceIdentity = deviceIdentity,
                // Read per gate call, as this root always has: the compile-time
                // `uploadBase` baked into the extension bundle.
                host = bakedUploadBase(),
                ledger = ledgerStore,
                upload = platform,
                gallery = gallery,
                discovery = discovery,
                // The per-event device manifest (capability `photo-sharing`): the extension PUTs it
                // SYNCHRONOUSLY in-cycle through its backend's manifest service (the former extension-local
                // `IosEnrollment` copy is dead — one uploader serves all).
                manifestStore = DeviceManifestService(files),
                manifestPublisher = backend.manifest,
                suppression = suppression,
                // Denylisted-album membership (capability `photo-sharing`): this tier's
                // stated failure posture is unchanged — a thrown lookup fails the cycle (retried on
                // the OS's next invocation).
                albumManager = albumManager,
                // The extension lets a failed lookup fail its cycle; the next invocation retries.
                albumLookupFailure = AlbumLookupFailure.FailCycle,
                albumCoordinator = albumCoordinator,
                token = { attestToken() },
                // A retry re-reads the shared item: the app may have renewed the token this copy still holds.
                freshToken = {
                    attestStore.reread()
                    attestToken()
                },
                log = log,
            )
    }

    /**
     * [process] as the iOS 26.1 `PHBackgroundResourceUploadProcessingResult` **raw value** — what the Swift principal
     * class forwards into `init?(rawValue:)` (settled forcing proof ① of migration step 12: the system type is
     * Swift-only, so the construction stays in Swift, but the decision — which case each [CycleResult] means — is the
     * tested `processingResultRawValue` mapping in `:domain` `ports/`).
     *
     * The one hand-written line this root keeps for the inbound port: the operating system invokes the cycle
     * synchronously and the process does not outlive it, so this blocks on the delegated [process]. Wiring only — no
     * branch here a second tier could answer differently. [process] and [onTerminate] themselves reach the core by
     * delegation (`docs/architecture.md`, "OS entry points cross an inbound port").
     */
    @PlatformEntry
    fun processRawValue(): Int = runBlocking { process() }.processingResultRawValue()
}

/**
 * The core's implementation of the extension's inbound port over this root's cycle. A top-level function because a
 * delegation expression is evaluated before the object's body; both providers resolve on first call.
 *
 * The root delegates to `extensionRootEntries()`, which the build takes from one of two directories
 * (`docs/architecture.md`, "A build-time-only module is contained by compilation, not by a runtime
 * check"): `src/entries`, which answers exactly this, or — only under `-Psnapsync.rig=true` — the rig's, which
 * answers this wrapped so a requested port-contract run takes the place of a cycle.
 */
internal fun productionExtensionEntries(): ExtensionEntries = extensionEntries(
    ports = { UploadExtensionRoot.ports },
    cycle = { UploadExtensionRoot.cycle },
    entryContext = IosEntryContext,
    rereadCredential = { UploadExtensionRoot.attestStore.reread() },
)
