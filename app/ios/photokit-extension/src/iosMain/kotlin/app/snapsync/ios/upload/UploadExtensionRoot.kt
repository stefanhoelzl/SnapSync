package app.snapsync.ios.upload

import app.snapsync.ports.AttestStore
import app.snapsync.attest.KeychainAttestStore
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.model.DENYLISTED_ALBUM_TITLES
import app.snapsync.album.IosAlbumManager
import app.snapsync.album.IosAlbumMapStore
import app.snapsync.ports.ConfigRead
import app.snapsync.config.KeychainConfigStore
import app.snapsync.keychain.KeychainDeviceIdentity
import app.snapsync.ports.KeychainUnavailable
import app.snapsync.model.Contribution
import app.snapsync.model.denormalizeAssetId
import app.snapsync.ports.SuppressionSource
import app.snapsync.downloadstore.iosSuppressionSource
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ios.discovery.IosDiscoveryStore
import app.snapsync.feature.upload.CycleGate
import app.snapsync.feature.upload.JoinedMembership
import app.snapsync.ports.CycleResult
import app.snapsync.feature.upload.cycleGate
import app.snapsync.model.UPLOAD_LIVENESS_DARWIN_NAME
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.ports.LedgerStore
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.feature.upload.SyncEngine
import app.snapsync.engine.iosLedgerStore
import app.snapsync.model.EdgeUploadRequestProvider
import app.snapsync.feature.membership.DeviceManifestProducer
import app.snapsync.gallery.IosDeviceManifestStore
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
import app.snapsync.model.deviceManifestAssetsFromResources
import app.snapsync.push.EventNotifier
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.feature.upload.ExtensionReconciler
import app.snapsync.membership.HttpDeviceFilesSource
import app.snapsync.membership.IosJoinedEventMarker
import app.snapsync.membership.darwinHttpClient
import app.snapsync.logging.FileLogWriter
import app.snapsync.logging.PublicNSLogWriter
import app.snapsync.model.invocation
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSBundle
import platform.CoreFoundation.CFNotificationCenterGetDarwinNotifyCenter
import platform.CoreFoundation.CFNotificationCenterPostNotification
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFStringEncodingUTF8

/**
 * The extension process's composition root — the single site that assembles the App-Group ledger
 * writer, the engine, the real S3 upload provider, the PhotoKit platform adapter, and the
 * [UploadCycle]. The Swift `@main` principal class calls [process] from its `process()` callback.
 *
 * Config is sourced fresh each cycle: the runtime event id from the shared Keychain
 * ([KeychainConfigStore]) combined with the compile-time upload host ([uploadHostFromBundle],
 * `BackgroundUploadURLBase`) into the edge upload provider. When no event has been joined yet (the
 * extension woke before setup), the cycle is skipped as a clean success — no job, no ledger write,
 * no crash.
 *
 * The ledger writer and platform are process-lifetime singletons (the extension is the single
 * `LedgerWriter`); only the engine, which depends on config, is built per cycle.
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
        Logger.withTag("UploadExtension").i { "=== extension process start build=${buildVersion()} ===" }
    }

    private val log = Logger.withTag("UploadExtension")

    /** Extension short-version(build) for the boot banner (capability `diagnostic-logging`, D5). */
    private fun buildVersion(): String {
        val bundle = NSBundle.mainBundle
        val short = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?"
        val build = bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "?"
        return "$short($build)"
    }

    // The cross-process liveness Darwin notification name, created once (a constant CFString for the
    // process lifetime). See the sync-status spec and the app-side observer in SnapSyncRoot.
    @OptIn(ExperimentalForeignApi::class)
    private val livenessName: CFStringRef? by lazy {
        CFStringCreateWithCString(null, UPLOAD_LIVENESS_DARWIN_NAME, kCFStringEncodingUTF8)
    }

    /** Post the payload-free liveness ding on the Darwin notify center (delivered immediately). */
    @OptIn(ExperimentalForeignApi::class)
    private fun postLivenessNotification() {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            livenessName,
            null,
            null,
            true,
        )
    }

    private val ledgerStore: LedgerStore by lazy { iosLedgerStore() }
    private val ledger: LedgerWriter by lazy { LedgerWriter(ledgerStore) }
    private val discovery: IosDiscovery by lazy {
        IosDiscovery(log, PhotoLibraryResourceEnumerator())
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
    private val configSource: KeychainConfigStore by lazy { KeychainConfigStore() }

    // Event album (capability `event-album`): the coordinator over the shared leave-surviving map and the
    // PhotoKit album manager. The extension only ever ADDS completed uploads (the app is the sole creator).
    // The manager is hoisted because the selection policy also reads it (denylisted-album membership).
    private val albumManager: IosAlbumManager by lazy { IosAlbumManager() }
    private val albumCoordinator: AlbumCoordinator by lazy {
        AlbumCoordinator(albumManager, IosAlbumMapStore())
    }

    // The stable per-install device id (shared Keychain, minted once): the `/files/devices/<deviceId>/`
    // byte-store partition the provider writes to, and the per-event device-manifest key. Resolved
    // once for the process lifetime.
    private val deviceId: String by lazy { KeychainDeviceIdentity().deviceId() }

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

    // Re-join reconciliation (capability `event-rejoin-reconciliation`), now extension-owned. Seeds
    // already-stored photos as COMPLETED before the producer runs so they are not re-uploaded; gated by
    // a persisted `joinedEventId` marker so a settled join performs no fetch. Fetches the event's
    // complete-asset listing over the Darwin HTTPS client; the host is the same compile-time
    // `BackgroundUploadURLBase` baked into the extension bundle.
    private val reconciler: ExtensionReconciler by lazy {
        ExtensionReconciler(
            // Seed dedup from the DEVICE's stored filenames (bytes are device-partitioned and
            // event-independent). The reconciler `resetTo`s the ledger to exactly those files
            // (clear-and-seed, dropping stale/phantom rows) and clears the cursor — see ExtensionReconciler.
            files = HttpDeviceFilesSource(httpClient, uploadHostFromBundle() ?: ""),
            ledger = ledgerStore,
            marker = IosJoinedEventMarker(),
            deviceId = deviceId,
            // Clear the discovery cursor on a re-join so the producer re-enumerates the whole library
            // (the cursor survives an app upgrade); the ledger dedups, so nothing already stored re-uploads.
            clearDiscoveryCursor = { discoveryStore.clearToken() },
            log = log,
        )
    }

    // The per-event device manifest (capability `device-manifest`): the extension is its SOLE writer
    // and PUTs it SYNCHRONOUSLY in-cycle (no background URLSession, no app involvement). Replaces the
    // retired per-asset manifest side channel. The uploader's host is the same compile-time
    // `BackgroundUploadURLBase`; the store persists the accumulator + last-uploaded JSON in the App Group.
    // Event-notify sender (capability `upload-completion-notify`): fired after a drained cycle that
    // completed uploads, so co-contributors are woken to download. Same compile-time host as the manifest.
    private val notifier: EventNotifier by lazy {
        EventNotifier(KtorPushHttpClient(httpClient), uploadHostFromBundle() ?: "")
    }

    private val deviceManifestProducer: DeviceManifestProducer by lazy {
        DeviceManifestProducer(
            store = IosDeviceManifestStore(),
            uploader = IosEnrollment(httpClient, uploadHostFromBundle() ?: ""),
            deviceId = deviceId,
        )
    }

    /**
     * The membership read, translated into the shared vocabulary — **this root's only contribution to
     * the decision**, and it is a translation, not a decision: the Keychain and the bundle are this
     * platform's, the skip-or-leave-or-run answer is [cycleGate]'s (capability `upload-lifecycle`).
     *
     * Re-read every cycle: the extension process outlives a single invocation, and a new event joined
     * by the app (another process) does not notify a StateFlow here — without the refresh the extension
     * would keep serving the event it read at construction.
     */
    private fun readGate(): CycleGate {
        val read = configSource.read()
        // Resolving the device id can fail the same way the config read can (both are Keychain items),
        // and every outcome needs it — the reconciler and the manifest producer each close over it, so
        // even the leave-side branch touches it. An unresolvable id is "I could not look", never "no id":
        // it belongs on the unreadable side of the roll-up, not in a fourth state.
        // Resolving here is free (the lazy caches success; a failure is simply retried next cycle).
        val idReadable = runCatching { deviceId }
            .onFailure { if (it !is KeychainUnavailable) throw it }
            .isSuccess
        val payload = (read as? ConfigRead.Joined)?.config
        return cycleGate(
            configReadable = read !is ConfigRead.Unavailable && idReadable,
            membership = payload?.let {
                JoinedMembership(
                    eventId = it.eventId,
                    contribution = Contribution.of(it.direction.includesUpload, it.minPhotoDate),
                    saveToAlbum = it.saveToAlbum,
                )
            },
            host = uploadHostFromBundle(),
            // The forensics for a skip. The decision is made in shared code that cannot see WHY the read
            // failed, and an unreadable config is invisible on a device except through this string.
            skipDetail = "protected data unavailable (config status=" +
                "${(read as? ConfigRead.Unavailable)?.status}, deviceId readable=$idReadable)",
        )
    }

    /**
     * The cycle. Long-lived (one per process): it re-reads the membership itself via [readGate] on each
     * `run()`, so nothing here is per-invocation.
     */
    private val cycle: UploadCycle by lazy {
        UploadCycle(
            readGate = ::readGate,
            // Bytes go to the device's event-independent partition (/files/devices/<deviceId>/…); the
            // eventId in `config` drives only the producer's event scope + the device-manifest write, not
            // the byte URL.
            engineFor = { config ->
                SyncEngine(EdgeUploadRequestProvider(config.host, deviceId) { attestToken() }, ledger)
            },
            ledger = ledger,
            platform = platform,
            store = discoveryStore,
            log = log,
            reconcile = { eventId -> reconciler.reconcile(eventId) },
            // Device manifest (capability `device-manifest`) from the cycle's OWN discovery — no second
            // PhotoKit enumeration. Bounding is the cycle's, not this root's.
            onDiscovery = { eventId, cutoff, discovery ->
                deviceManifestProducer.produce(
                    eventId = eventId,
                    startDate = cutoff, // per-device capture-date cutoff (photo-selection-policy)
                    discovered = deviceManifestAssetsFromResources(discovery.resources),
                    removedAssetIds = discovery.removedAssetIds.toSet(),
                    fullEnumeration = discovery.fullEnumeration,
                )
            },
            suppressedAssetIds = { suppression.suppressedLocalIds() },
            albumExcludedAssetIds = { cutoff -> albumManager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff) },
            onBatchUploaded = { eventId -> notifier.notify(eventId) },
            // The event album (capability `event-album`): the cycle applies the membership's opt-in, which
            // arrived with the gate — this lambda only reverses the normalized `assetId` (`_`→`/`).
            placeInAlbum = { eventId, assetIds ->
                albumCoordinator.place(eventId, assetIds.map(::denormalizeAssetId))
            },
        )
    }

    /**
     * Run one cycle and return its [CycleResult] — `COMPLETED` (drained, cursor advanced), `PROCESSING`
     * (call me again, cursor un-advanced), `SKIPPED`, or `FAILED`. The Swift shell maps it to the
     * system's terminal/processing result.
     *
     * What is left here is exactly what cannot be shared with the other upload tier: the synchronous
     * `runBlocking` contract (the OS invokes this and the process does not outlive it), the cross-process
     * liveness ding (this tier writes the ledger in a *different* process from the UI), and the
     * pending→`PROCESSING` requeue (this tier alone cannot observe a completion while not running).
     * Everything else the body used to do now lives in [UploadCycle], where both tiers reach it and a
     * test can too.
     */
    fun process(): CycleResult = log.invocation("process", result = { "$it" }) { runBlocking {
        val result = runCatching { cycle.run() }
            .onSuccess { log.i { "process: cycle finished — $it" } }
            .getOrElse {
                log.e(it) { "process cycle failed" }
                CycleResult.FAILED
            }
        // Tell the app (if foreground) the ledger may have changed so upload status refreshes live
        // (spec: sync-status): a payload-free cross-process Darwin ding, posted after EVERY run so both a
        // rising in-flight count and a drain are signalled. Best-effort — a post failure never affects
        // the returned result. The `LedgerStore` itself still posts nothing; this is composition-root.
        runCatching { postLivenessNotification() }.onFailure { log.w(it) { "liveness post failed" } }
        // The OS invokes the extension lazily (on library changes), not when an upload quietly
        // finishes — so a drained cycle that returns COMPLETED leaves already-succeeded jobs
        // un-acknowledged until the next change. While the ledger still has pending (in-flight)
        // rows, return PROCESSING to request another invocation so their completions are recorded
        // promptly; report COMPLETED only once everything is uploaded (pending == 0), so the system
        // then rests. (The OS throttles re-invocation, so this polls at its cadence, not in a loop.)
        if (result == CycleResult.COMPLETED) {
            val pending = ledgerStore.aggregates().pending
            if (pending > 0) {
                log.i { "process: $pending pending — requesting re-invocation" }
                return@runBlocking CycleResult.PROCESSING
            }
        }
        result
    } }
}
