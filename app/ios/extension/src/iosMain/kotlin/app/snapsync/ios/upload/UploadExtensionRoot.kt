package app.snapsync.ios.upload

import app.snapsync.ports.AttestStore
import app.snapsync.attest.KeychainAttestStore
import app.snapsync.compose.UploadPorts
import app.snapsync.compose.uploadCore
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.model.DENYLISTED_ALBUM_TITLES
import app.snapsync.album.IosAlbumManager
import app.snapsync.album.IosAlbumMapStore
import app.snapsync.config.FileBackedConfigStore
import app.snapsync.config.bakedUploadBase
import app.snapsync.keychain.DeviceIdentityRole
import app.snapsync.keychain.KeychainDeviceIdentity
import app.snapsync.ports.SuppressionSource
import app.snapsync.downloadstore.iosSuppressionSource
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ios.discovery.IosDiscoveryStore
import app.snapsync.join.HttpEnrollment
import app.snapsync.ports.CycleResult
import app.snapsync.ports.processingResultRawValue
import app.snapsync.ports.requeueWhilePending
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.ports.LedgerStore
import app.snapsync.engine.iosLedgerStore
import app.snapsync.gallery.IosDeviceManifestStore
import app.snapsync.gallery.PhotoKitCandidateSource
import app.snapsync.feature.push.EventNotifier
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.membership.HttpDeviceFilesSource
import app.snapsync.membership.IosJoinedEventMarker
import app.snapsync.membership.darwinHttpClient
import app.snapsync.logging.FileLogWriter
import app.snapsync.logging.SentryCrashReporting
import app.snapsync.logging.appBuildVersion
import app.snapsync.logging.PublicNSLogWriter
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
 * App-Group config file ([FileBackedConfigStore] — writes are file-only since the migration
 * finale ended the 11a Keychain write-through; the read keeps the legacy-Keychain migration
 * fallback until the post-ship Stage-2 change, so this extension can be the process that migrates
 * a pre-file device on the OS's first post-update invocation) combined with the compile-time upload host
 * ([bakedUploadBase], `BackgroundUploadURLBase`). When no event has been joined yet (the
 * extension woke before setup), the cycle is skipped as a clean success — no job, no ledger write,
 * no crash.
 *
 * The composed cycle and platform are process-lifetime singletons (the extension is the single
 * ledger record-writer on its tier); the engine, which depends on config, is built per cycle
 * inside `uploadCore`.
 */
object UploadExtensionRoot {

    init {
        // Route kermit through a public NSLog writer AND a file writer. NSLog turns out to be
        // redacted as `<private>` on current iOS (dynamic format strings are private), so the file
        // writer (Documents/debug.log, pulled via `pymobiledevice3 apps pull`) is the reliable
        // channel for reading the extension's logs on device.
        Logger.setLogWriters(PublicNSLogWriter(), FileLogWriter())
        // Boot banner (capability `diagnostic-logging`, D5) — the extension is a separate, short-lived
        // process; name it + the build version so its file is unambiguous. `log` isn't assigned yet.
        Logger.withTag("UploadExtension").i { "=== extension process start build=${appBuildVersion()} ===" }
        // The BAKED backend this build uploads to — the same diagnostic the app emits, and it matters
        // more here: this process IS the upload path, and pointing a build at a different backend
        // without `SNAPSYNC_RESET_STATE` leaves the ledger claiming everything is already COMPLETED, so
        // the cycle enumerates and enqueues nothing with no error anywhere. Read beside this process's
        // own `enumeration: N seen, X new, Y already-uploaded`, a changed host names the cause at once.
        Logger.withTag("UploadExtension").i { "[boot] upload base = ${bakedUploadBase()}" }
    }

    private val log = Logger.withTag("UploadExtension")

    private val ledgerStore: LedgerStore by lazy { iosLedgerStore() }
    private val discovery: IosDiscovery by lazy {
        IosDiscovery(log, PhotoKitCandidateSource())
    }
    private val platform: IosPhotoKitUploadPlatform by lazy {
        IosPhotoKitUploadPlatform(log, discovery)
    }
    private val discoveryStore: IosDiscoveryStore by lazy { IosDiscoveryStore() }

    // The app-written download store, opened read-only through the NARROWED SuppressionSource type
    // (capability `download-store`): only `suppressedLocalIds()`, never the full DownloadStore surface,
    // so the extension is compile-prevented from writing it or reading beyond the suppression set. It
    // only reads which downloaded-then-imported assets must not be re-uploaded.
    private val suppression: SuppressionSource by lazy { iosSuppressionSource() }
    private val configSource: FileBackedConfigStore by lazy { FileBackedConfigStore() }

    // Event album (capability `event-album`): the coordinator over the shared leave-surviving map and the
    // PhotoKit album manager. The extension only ever ADDS completed uploads (the app is the sole creator).
    // The manager is hoisted because the selection policy also reads it (denylisted-album membership).
    private val albumManager: IosAlbumManager by lazy { IosAlbumManager() }
    private val albumCoordinator: AlbumCoordinator by lazy {
        AlbumCoordinator(albumManager, IosAlbumMapStore())
    }

    // The stable per-install device id (shared Keychain access group, addressed by name): the
    // `/files/devices/<deviceId>/` byte-store partition the provider writes to, and the per-event
    // device-manifest key. Resolved once for the process lifetime.
    //
    // READ_ONLY — this process neither mints nor adopts (capability `device-identity`). It cannot tell
    // "no identity yet" from "the app's identity is not reachable from here", and guessing is what gave
    // this device two identities: the extension uploaded under one while the app reconciled under the
    // other, so the app re-imported every photo the device itself had uploaded. Absence raises
    // `DeviceIdentityAbsent` and the cycle gate skips, exactly as it does for an unreadable Keychain.
    private val deviceId: String by lazy {
        KeychainDeviceIdentity(DeviceIdentityRole.READ_ONLY).deviceId()
    }

    // One shared Darwin (NSURLSession) HTTP client for both in-cycle network calls (the reconcile
    // listing GET and the device.json PUT) — a single client avoids running two NSURLSession-backed
    // engines under the same `runBlocking`.
    /**
     * The device token (capability `device-attestation`), read from the **shared Keychain** — the app put
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
     * Non-throwing: the Keychain is unreadable before the first unlock since boot, and this is called on a
     * background wake, which is exactly when that happens. A null token is a `401`, which is retryable; a
     * thrown error here would take down the cycle.
     */
    private val attestStore: AttestStore by lazy { KeychainAttestStore() }

    private fun attestToken(): String? = runCatching { attestStore.token() }.getOrNull()

    private val httpClient by lazy {
        darwinHttpClient(
            token = { attestToken() },
            // The extension cannot attest, so it cannot recover on its own — but it CAN drop a token the
            // backend has rejected. That is what makes the app re-mint at its next wake: `isStale(null)` is
            // true, while a rejected-but-unexpired token would have looked perfectly fine forever.
            onRejected = { runCatching { attestStore.clearToken() } },
        )
    }

    // Event-notify sender (capability `upload-completion-notify`; `:domain` feature/push since the
    // migration finale re-homed it): fired after a drained cycle that completed uploads, so
    // co-contributors are woken to download. Same compile-time host as the manifest. Root-constructed
    // over this process's shared HTTP client; `uploadCore` takes the notify as a stated lambda.
    private val notifier: EventNotifier by lazy {
        EventNotifier(KtorPushHttpClient(httpClient), bakedUploadBase())
    }

    // The extension's process scope, handed to the shared composition per its contract (`module-
    // architecture`, "the composition functions SHALL receive a CoroutineScope"). The upload subset
    // consumes no scope until step 8 installs the port-state-transition subscriptions in compose/;
    // this extension's own execution model stays the synchronous per-`process()` `runBlocking` below.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The cycle — assembled by the SHARED composition `uploadCore` (spec `module-architecture`, "One
     * shared composition"): this root supplies only its ports and platform reads (the Keychain
     * three-state `ConfigReader`, the identity resolve, the compile-time bundle host, the PhotoKit
     * platform, the App-Group stores, and the generic HTTP adapters). The entry-gate translation,
     * the reconciler, the device-manifest producer, and the engine wiring live in `uploadCore` —
     * identical for the app-driven tier and the world harness, so this tier cannot carry cycle
     * wiring another tier lacks. Long-lived (one per process): the cycle re-reads the membership
     * per `run()`, so nothing here is per-invocation.
     */
    private val cycle: UploadCycle by lazy {
        uploadCore(
            scope,
            UploadPorts(
                crashReporting = SentryCrashReporting(),
                config = configSource,
                // The lazy caches the first success; a failure throws `KeychainUnavailable` and is
                // retried next cycle — the gate's probe puts it on the unreadable side of the roll-up.
                deviceId = { deviceId },
                // Read per gate call, as this root always has: the compile-time
                // `BackgroundUploadURLBase` baked into the extension bundle.
                host = { bakedUploadBase() },
                ledger = ledgerStore,
                transfer = platform,
                discoveryStore = discoveryStore,
                // Re-join reconciliation seed (capability `event-rejoin-reconciliation`): the
                // device's stored-file listing over the Darwin HTTPS client, same compile-time host.
                deviceFiles = HttpDeviceFilesSource(httpClient, bakedUploadBase()),
                joinedMarker = IosJoinedEventMarker(),
                // The per-event device manifest (capability `device-manifest`): the extension is its
                // SOLE writer and PUTs it SYNCHRONOUSLY in-cycle via the generic `HttpEnrollment`
                // (the former extension-local `IosEnrollment` copy is dead — one uploader serves all).
                manifestStore = IosDeviceManifestStore(),
                enrollment = HttpEnrollment(httpClient, bakedUploadBase()),
                suppression = suppression,
                // Denylisted-album membership (capability `photo-selection-policy`): this tier's
                // stated failure posture is unchanged — a thrown lookup fails the cycle (retried on
                // the OS's next invocation).
                albumExcludedAssetIds = { cutoff -> albumManager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff.at.iso) },
                albumCoordinator = albumCoordinator,
                token = { attestToken() },
                onBatchUploaded = { eventId -> notifier.notify(eventId) },
                log = log,
            ),
        )
    }

    /**
     * Run one cycle and return its [CycleResult] — `COMPLETED` (drained, cursor advanced), `PROCESSING`
     * (call me again, cursor un-advanced), `SKIPPED`, or `FAILED`.
     *
     * What is left here is exactly what cannot be shared with the other upload tier: the synchronous
     * `runBlocking` contract (the OS invokes this and the process does not outlive it) and the
     * pending→`PROCESSING` requeue (this tier alone cannot observe a completion while not running).
     * Everything else the body used to do now lives in [UploadCycle], where both tiers reach it and a
     * test can too. (The cross-process liveness ding this root used to post died at migration step
     * 12 — the app's foreground-gated `aggregates()` poll replaced it; spec `sync-status`.)
     */
    fun process(): CycleResult = log.invocation("process", result = { "$it" }) { runBlocking {
        val result = runCatching { cycle.run() }
            .onSuccess { log.i { "process: cycle finished — $it" } }
            .getOrElse {
                log.e(it) { "process cycle failed" }
                CycleResult.FAILED
            }
        // The pending→PROCESSING requeue rule is `requeueWhilePending` (`:domain` ports/, beside
        // the raw-value mapping — drained there at the migration finale so it is tested); this
        // wiring supplies only the ledger read and the debug.log line.
        result.requeueWhilePending(
            pending = { ledgerStore.aggregates().pending },
            onRequeue = { open -> log.i { "process: $open pending — requesting re-invocation" } },
        )
    } }

    /**
     * [process] as the iOS 26.1 `PHBackgroundResourceUploadProcessingResult` **raw value** — what the
     * Swift principal class forwards into `init?(rawValue:)` (settled forcing proof ① of migration
     * step 12: the system type is Swift-only, so the construction stays in Swift, but the decision —
     * which case each [CycleResult] means — is the tested `processingResultRawValue` mapping in
     * `:domain` `ports/`). Wiring only: no branch here a second tier could answer differently.
     */
    fun processRawValue(): Int = process().processingResultRawValue()
}
