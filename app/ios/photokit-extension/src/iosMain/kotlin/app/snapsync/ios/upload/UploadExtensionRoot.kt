package app.snapsync.ios.upload

import app.snapsync.config.KeychainConfigStore
import app.snapsync.deviceid.KeychainDeviceIdentity
import app.snapsync.downloadstore.SuppressionSource
import app.snapsync.downloadstore.iosSuppressionSource
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ios.discovery.IosDiscoveryStore
import app.snapsync.upload.CycleResult
import app.snapsync.upload.UPLOAD_LIVENESS_DARWIN_NAME
import app.snapsync.upload.UploadCycle
import app.snapsync.upload.buildUploadConfig
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.iosLedgerBackend
import app.snapsync.uploadurl.EdgeUploadRequestProvider
import app.snapsync.gallery.DeviceManifestProducer
import app.snapsync.gallery.IosDeviceManifestStore
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
import app.snapsync.gallery.deviceManifestAssetsFromResources
import app.snapsync.push.EventNotifier
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.rejoin.ExtensionReconciler
import app.snapsync.rejoin.HttpDeviceFilesSource
import app.snapsync.rejoin.darwinHttpClient
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.CoreFoundation.CFNotificationCenterGetDarwinNotifyCenter
import platform.CoreFoundation.CFNotificationCenterPostNotification
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFStringEncodingUTF8

/**
 * Upper bound on the synchronous in-cycle device.json PUT (capability `device-manifest`). The
 * background-upload extension runner has a hard ~3-minute OS runtime cap; a network call under
 * `runBlocking` that hangs past it gets the worker force-killed (error 50001) before uploads are
 * handed off. The byte-upload jobs are created BEFORE this, so they are safe; this bound keeps a
 * slow/hung manifest PUT from ever blowing the budget.
 */
private const val DEVICE_MANIFEST_TIMEOUT_MS = 12_000L

// Upper bound on the synchronous in-cycle notify POST (capability `upload-completion-notify`) — bounded
// like the manifest PUT so a slow/hung host can never stall the cycle to the OS's force-kill.
private const val NOTIFY_TIMEOUT_MS = 8_000L

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
    }

    private val log = Logger.withTag("UploadExtension")

    // The cross-process liveness Darwin notification name, created once (a constant CFString for the
    // process lifetime). See design.md §2.3 and the app-side observer in SnapSyncRoot.
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

    private val ledgerBackend: LedgerBackend by lazy { iosLedgerBackend() }
    private val ledger: LedgerWriter by lazy { LedgerWriter(ledgerBackend) }
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

    // The stable per-install device id (shared Keychain, minted once): the `/files/devices/<deviceId>/`
    // byte-store partition the provider writes to, and the per-event device-manifest key. Resolved
    // once for the process lifetime.
    private val deviceId: String by lazy { KeychainDeviceIdentity().deviceId() }

    // One shared Darwin (NSURLSession) HTTP client for both in-cycle network calls (the reconcile
    // listing GET and the device.json PUT) — a single client avoids running two NSURLSession-backed
    // engines under the same `runBlocking`.
    private val httpClient by lazy { darwinHttpClient() }

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
            ledger = ledgerBackend,
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
            uploader = IosDeviceManifestUploader(httpClient, uploadHostFromBundle() ?: ""),
            deviceId = deviceId,
        )
    }

    /**
     * Run one adjudicate→discover cycle and return its [CycleResult] — `COMPLETED` (drained, cursor
     * advanced), `PROCESSING` (the in-flight cap was hit; call me again, cursor un-advanced), or
     * `FAILED`. The Swift shell maps it to the system's terminal/processing result. Blocks the
     * extension's worker until done — appropriate for the synchronous `process()` contract. The
     * engine is the sole ledger writer; the cycle reads the same ledger to reconstruct lifecycle jobs.
     */
    fun process(): CycleResult = runBlocking {
        // Re-read the Keychain each cycle: the extension process outlives a single invocation, and a
        // new event joined by the app (another process) does not notify this StateFlow — without the
        // refresh the extension keeps uploading to the event it read at construction (a stale,
        // previously-joined event). This is what makes "config is sourced fresh each cycle" true.
        configSource.reload()
        val payload = configSource.config.value
        val host = uploadHostFromBundle()

        // Re-join reconciliation runs HERE, before any upload job is created (capability
        // `event-rejoin-reconciliation`): on a (re)join it seeds already-stored photos as COMPLETED so
        // the producer does not re-upload them, resets the private ledger on an event switch/leave, and
        // — if the listing fetch fails — defers this cycle (uploads nothing, leaves the marker unset to
        // retry). A settled join (marker matches the configured event) does no fetch and returns true.
        val mayUpload = runCatching { reconciler.reconcile(payload?.eventId) }
            .getOrElse { log.e(it) { "reconcile failed — deferring uploads this cycle" }; false }

        val config = buildUploadConfig(payload?.eventId, host)
        if (config == null || !mayUpload) {
            // Not joined yet (no event id), a missing baked host, a leave reset, or a deferred reconcile
            // — nothing to upload. A clean no-op completion, never a failure; the run re-tries next cycle.
            log.i {
                "skipping cycle — eventId present=${payload != null}, " +
                    "host present=${!host.isNullOrEmpty()}, mayUpload=$mayUpload"
            }
            return@runBlocking CycleResult.COMPLETED
        }
        log.i { "process: config present and reconciled — running cycle" }
        val engine = SyncEngine(
            // Bytes go to the device's event-independent partition (/files/devices/<deviceId>/…); the eventId
            // in `config` drives only the producer's event scope + the device-manifest write, not the
            // byte URL.
            EdgeUploadRequestProvider(config.host, deviceId),
            ledger,
        )
        // Device manifest (capability `device-manifest`) is produced from the cycle's OWN discovery —
        // no second PhotoKit enumeration (that pass hung the lean extension). The hook runs once per
        // fully-drained cycle, AFTER the byte-upload jobs are created, and the PUT is strictly bounded
        // by `withTimeout` so it can never stall the cycle to the OS's force-kill; it is best-effort
        // and write-only in v1, so any failure/timeout just retries next cycle (skip-if-unchanged
        // makes that cheap).
        val cycle = UploadCycle(
            engine, ledger, platform, discoveryStore, log,
            onDiscovery = { discovery ->
                runCatching {
                    withTimeout(DEVICE_MANIFEST_TIMEOUT_MS) {
                        deviceManifestProducer.produce(
                            eventId = config.eventId,
                            startDate = null, // whole-library scope (the date filter is deferred)
                            discovered = deviceManifestAssetsFromResources(discovery.resources),
                            removedAssetIds = discovery.removedAssetIds.toSet(),
                            fullEnumeration = discovery.fullEnumeration,
                        )
                    }
                }.onFailure { log.w(it) { "device.json production failed/timed out this cycle" } }
            },
            // Notify the event's members AFTER the manifest PUT (capability `upload-completion-notify`):
            // that is the only point the union reflects the just-completed assets, so a woken recipient
            // finds them. Fires only on a fully-drained cycle with >= 1 completion (gated in UploadCycle),
            // bounded + best-effort so a hung host can never stall the cycle.
            onBatchUploaded = {
                runCatching { withTimeout(NOTIFY_TIMEOUT_MS) { notifier.notify(config.eventId) } }
                    .onFailure { log.w(it) { "event notify failed/timed out this cycle" } }
            },
            // Echo-suppression: never re-upload an asset this device downloaded + imported.
            suppressedAssetIds = { suppression.suppressedLocalIds() },
        )
        val result = runCatching { cycle.run() }
            .onSuccess { log.i { "process: cycle finished — $it" } }
            .getOrElse {
                log.e(it) { "process cycle failed" }
                CycleResult.FAILED
            }
        // Tell the app (if foreground) the ledger may have changed so upload status refreshes live
        // (design.md §2.3): a payload-free cross-process Darwin ding, posted after EVERY run so both a
        // rising in-flight count and a drain are signalled. Best-effort — a post failure never affects
        // the returned result. The `LedgerBackend` itself still posts nothing; this is composition-root.
        runCatching { postLivenessNotification() }.onFailure { log.w(it) { "liveness post failed" } }
        // The OS invokes the extension lazily (on library changes), not when an upload quietly
        // finishes — so a drained cycle that returns COMPLETED leaves already-succeeded jobs
        // un-acknowledged until the next change. While the ledger still has pending (in-flight)
        // rows, return PROCESSING to request another invocation so their completions are recorded
        // promptly; report COMPLETED only once everything is backed up (pending == 0), so the system
        // then rests. (The OS throttles re-invocation, so this polls at its cadence, not in a loop.)
        if (result == CycleResult.COMPLETED) {
            val pending = ledgerBackend.aggregates().pending
            if (pending > 0) {
                log.i { "process: $pending pending — requesting re-invocation" }
                return@runBlocking CycleResult.PROCESSING
            }
        }
        result
    }
}
