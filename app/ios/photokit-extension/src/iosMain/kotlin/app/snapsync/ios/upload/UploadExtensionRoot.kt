package app.snapsync.ios.upload

import app.snapsync.attest.AttestStore
import app.snapsync.attest.KeychainAttestStore
import app.snapsync.album.AlbumCoordinator
import app.snapsync.album.DENYLISTED_ALBUM_TITLES
import app.snapsync.album.IosAlbumManager
import app.snapsync.album.IosAlbumMapStore
import app.snapsync.config.ConfigRead
import app.snapsync.config.KeychainConfigStore
import app.snapsync.keychain.KeychainUnavailable
import app.snapsync.deviceid.KeychainDeviceIdentity
import app.snapsync.gallery.denormalizeAssetId
import app.snapsync.downloadstore.SuppressionSource
import app.snapsync.downloadstore.iosSuppressionSource
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ios.discovery.IosDiscoveryStore
import app.snapsync.upload.CycleGate
import app.snapsync.upload.CycleResult
import app.snapsync.upload.cycleGate
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
import app.snapsync.membership.ExtensionReconciler
import app.snapsync.membership.HttpDeviceFilesSource
import app.snapsync.membership.IosJoinedEventMarker
import app.snapsync.membership.darwinHttpClient
import app.snapsync.logging.FileLogWriter
import app.snapsync.logging.PublicNSLogWriter
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSBundle
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
    fun process(): CycleResult = log.invocation("process", result = { "$it" }) { runBlocking {
        // Re-read the Keychain each cycle: the extension process outlives a single invocation, and a
        // new event joined by the app (another process) does not notify this StateFlow — without the
        // refresh the extension keeps serving the event it read at construction (a stale, previously
        // joined event). This is what makes "config is sourced fresh each cycle" true.
        //
        // THREE states, not two (capability `event-link`). The OS invokes this extension when the
        // device is idle — which usually means LOCKED — and a locked device could not read the Keychain
        // at all before this was fixed. That read failure arrived here as `null` = "no event configured"
        // = a LEAVE, so the reconciler below cleared the join marker on essentially every invocation,
        // and the next readable cycle paid for a full re-join (device LIST + ledger clear-and-seed +
        // discovery-cursor reset → a complete PhotoKit re-enumeration). The marker never settled.
        val read = configSource.read()
        // Resolving the device id can fail the same way the config read can (both are Keychain items),
        // and every branch below needs it — the reconciler and the manifest producer each close over it,
        // so even the leave-side branch touches it. An unresolvable id is "I could not look", never
        // "no id": treat it exactly like an unreadable config rather than letting it out of `process()`.
        // Resolving here is free (the lazy caches success; a failure is simply retried next cycle).
        val idReadable = runCatching { deviceId }
            .onFailure { if (it !is KeychainUnavailable) throw it }
            .isSuccess

        // The decision itself is tested in :capability:upload — this root is glue (it is wiring-only and
        // untested by the project's hard rule, which is exactly how a "config is null → clear the join
        // marker" decision came to live in an untested file in the first place).
        val readable = read !is ConfigRead.Unavailable && idReadable
        val payload = (read as? ConfigRead.Joined)?.config
        val host = uploadHostFromBundle()

        val gate = cycleGate(readable, payload?.eventId, host)
        if (gate is CycleGate.Skip) {
            // Unreadable ≠ left. Touch NOTHING: no reconcile, no marker clear, no cursor reset, no jobs.
            // A clean completion; the next cycle — or the next unlock — retries.
            log.w {
                val status = (read as? ConfigRead.Unavailable)?.status
                "skipping cycle — protected data unavailable (config status=$status, deviceId " +
                    "readable=$idReadable). NOT treating this as a leave; nothing minted, nothing " +
                    "reconciled, marker untouched."
            }
            return@runBlocking CycleResult.COMPLETED
        }
        val config = (gate as? CycleGate.Run)?.config
        if (payload == null || config == null) {
            // Definitively not joined: no item at all, a legacy item that cannot decode (capability
            // `photo-selection-policy`, where reading as no-config is the deliberate safe outcome), a missing
            // baked host, or a leave. Nothing to upload, so no cycle is built — a clean no-op completion,
            // never a failure.
            //
            // The reconciler still runs here, because this is where a leave clears the `joinedEventId`
            // marker (capability `event-rejoin-reconciliation`) — it keeps the ledger, cursor, and
            // accumulator intact so a later provision of any event dedups against them. No cycle exists
            // to carry this, so it stays here; the JOINED case reconciles inside the cycle.
            // Reaching this branch now means the config really IS absent, never merely unread.
            runCatching { reconciler.reconcile(null) }
                .onFailure { log.w(it) { "leave-side marker clear failed" } }
            log.i {
                "skipping cycle — eventId present=${payload != null}, host present=${!host.isNullOrEmpty()}"
            }
            return@runBlocking CycleResult.COMPLETED
        }
        log.i { "process: config present — running cycle (reconcile runs inside it)" }
        val engine = SyncEngine(
            // Bytes go to the device's event-independent partition (/files/devices/<deviceId>/…); the eventId
            // in `config` drives only the producer's event scope + the device-manifest write, not the
            // byte URL.
            EdgeUploadRequestProvider(config.host, deviceId) { attestToken() },
            ledger,
        )
        // Device manifest (capability `device-manifest`) is produced from the cycle's OWN discovery —
        // no second PhotoKit enumeration (that pass hung the lean extension). The hook runs once per
        // fully-drained cycle, AFTER the byte-upload jobs are created, and the PUT is strictly bounded
        // by `withTimeout` so it can never stall the cycle to the OS's force-kill; it is best-effort
        // and write-only in v1, so any failure/timeout just retries next cycle (skip-if-unchanged
        // makes that cheap).
        // Capture-date cutoff (capability `photo-selection-policy`): this device's per-membership
        // `EventConfig.minPhotoDate` (v1: the single joined event) — always present. It scopes the
        // discovery walk, the byte-upload filter (below), and the device-manifest projection
        // (`startDate`), so the bytes uploaded equal the assets shared into the event union.
        val cutoff = payload.minPhotoDate
        val cycle = UploadCycle(
            engine, ledger, platform, discoveryStore, log,
            // Re-join reconciliation (capability `event-rejoin-reconciliation`) now runs INSIDE the shared
            // cycle, before any upload job is created — the same gate, moved from this root into
            // `:capability:upload` so BOTH tiers get it (the app-driven tier shipped without one). The
            // cycle stays event-agnostic; this lambda closes over the eventId, like the notify hook.
            reconcile = { reconciler.reconcile(config.eventId) },
            onDiscovery = { discovery ->
                runCatching {
                    withTimeout(DEVICE_MANIFEST_TIMEOUT_MS) {
                        deviceManifestProducer.produce(
                            eventId = config.eventId,
                            startDate = cutoff, // per-device capture-date cutoff (photo-selection-policy)
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
            // Denylisted-album membership (capability `photo-selection-policy`): photos a messaging app
            // saved into its own album were received, not taken. The POLICY (which titles) is the
            // `commonMain` constant; this only performs the lookup. Cost is O(albums), not O(assets).
            albumExcludedAssetIds = { albumManager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff) },
            // Per-device capture-date cutoff: pre-cutoff photos' bytes never upload (photo-selection-policy).
            photoCutoff = { cutoff },
            // Event album (capability `event-album`): add this cycle's completed own photos to the event
            // album (extension tier, ≥26.1 — verified on device). Recover each raw `localIdentifier` from
            // the normalized `assetId` (reverse `_`→`/`) to fetch the PHAsset. Gated on the opt-in.
            placeInAlbum = { assetIds ->
                if (payload.saveToAlbum) {
                    albumCoordinator.place(config.eventId, assetIds.map(::denormalizeAssetId))
                }
            },
        )
        val result = runCatching { cycle.run() }
            .onSuccess { log.i { "process: cycle finished — $it" } }
            .getOrElse {
                log.e(it) { "process cycle failed" }
                CycleResult.FAILED
            }
        // Tell the app (if foreground) the ledger may have changed so upload status refreshes live
        // (spec: sync-status): a payload-free cross-process Darwin ding, posted after EVERY run so both a
        // rising in-flight count and a drain are signalled. Best-effort — a post failure never affects
        // the returned result. The `LedgerBackend` itself still posts nothing; this is composition-root.
        runCatching { postLivenessNotification() }.onFailure { log.w(it) { "liveness post failed" } }
        // The OS invokes the extension lazily (on library changes), not when an upload quietly
        // finishes — so a drained cycle that returns COMPLETED leaves already-succeeded jobs
        // un-acknowledged until the next change. While the ledger still has pending (in-flight)
        // rows, return PROCESSING to request another invocation so their completions are recorded
        // promptly; report COMPLETED only once everything is uploaded (pending == 0), so the system
        // then rests. (The OS throttles re-invocation, so this polls at its cadence, not in a loop.)
        if (result == CycleResult.COMPLETED) {
            val pending = ledgerBackend.aggregates().pending
            if (pending > 0) {
                log.i { "process: $pending pending — requesting re-invocation" }
                return@runBlocking CycleResult.PROCESSING
            }
        }
        result
    } }
}
