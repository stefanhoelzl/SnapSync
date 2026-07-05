package app.snapsync.ios

import app.snapsync.config.KeychainConfigStore
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.SyncEngine
import app.snapsync.gallery.DeviceManifestProducer
import app.snapsync.gallery.IosDeviceManifestStore
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
import app.snapsync.gallery.deviceManifestAssetsFromResources
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ios.discovery.IosDiscoveryStore
import app.snapsync.ios.urlsession.IosBackgroundScheduler
import app.snapsync.ios.urlsession.IosUrlSessionUploadPlatform
import app.snapsync.push.EventNotifier
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.upload.BackgroundUploadPump
import app.snapsync.upload.CycleResult
import app.snapsync.upload.UploadCycle
import app.snapsync.upload.buildUploadConfig
import app.snapsync.uploadurl.EdgeUploadRequestProvider
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// Upper bounds on the two synchronous in-cycle HTTP calls (manifest PUT, notify POST) — strictly
// bounded by `withTimeout` so a slow/hung host can never stall the cycle to a force-kill; both are
// best-effort and retried next cycle. Mirrors the extension root's device-manifest budget.
private const val DEVICE_MANIFEST_TIMEOUT_MS = 12_000L
private const val NOTIFY_TIMEOUT_MS = 8_000L

/**
 * The app-driven (iOS 18–26.0) upload tier's composition root — the app-process analogue of
 * `UploadExtensionRoot`, but driving the shared `:capability:upload` `UploadCycle` over a background
 * `URLSession` instead of the PhotoKit OS-job queue. Assembled by [SnapSyncRoot] when
 * `backgroundUploadSupported()` is false (or the dev force flag is set). On this tier the **app is the
 * single `LedgerWriter`** — there is no extension process, so the cross-process single-writer concern
 * does not apply (`sync-ledger`: the record-writer's process placement is a platform binding).
 *
 * [BackgroundUploadPump] is the in-app reimplementation of the OS scheduler; the four triggers are
 * forwarded from [SnapSyncRoot] (foreground, `BGProcessingTask`, background-session relaunch,
 * per-completion). Config is re-read each cycle so a newly-joined event takes effect.
 */
class UrlSessionUploadController(
    private val scope: CoroutineScope,
    private val ledgerBackend: LedgerBackend,
    private val configSource: KeychainConfigStore,
    private val deviceId: String,
    private val host: String,
    private val log: Logger,
    // The shared Darwin HTTP client — used for the in-cycle device-manifest PUT and the event-notify POST.
    private val httpClient: HttpClient,
    // Echo-suppression (capability `photo-download`): the `assetId`s of foreign assets this device
    // downloaded + imported. Read once per cycle so an imported foreign asset is never re-uploaded (the
    // echo) — essential now that this tier writes the device manifest and so appears in the union.
    private val suppressedAssetIds: suspend () -> Set<String>,
    // False on the dev/test-forced (simulator) path — the sim can't run a background NSURLSession.
    private val useBackgroundSession: Boolean = true,
) {
    companion object {
        const val SESSION_IDENTIFIER = "app.snapsync.upload.session"
        const val HEARTBEAT_TASK_IDENTIFIER = "app.snapsync.upload.heartbeat"
    }

    private val ledger = LedgerWriter(ledgerBackend)
    private val discovery = IosDiscovery(log, PhotoLibraryResourceEnumerator())
    private val discoveryStore = IosDiscoveryStore()
    private val scheduler = IosBackgroundScheduler(log, HEARTBEAT_TASK_IDENTIFIER)

    // The per-event device manifest (capability `device-manifest`) — on this tier the APP is its sole
    // writer (no extension process). Produced from the cycle's OWN discovery (no second enumeration),
    // PUT synchronously in-cycle and bounded by `withTimeout`. Without it this tier's uploads would never
    // appear in the event union (union = manifest ∩ stored bytes), so no other device could download them.
    private val deviceManifestProducer = DeviceManifestProducer(
        store = IosDeviceManifestStore(),
        uploader = IosDeviceManifestUploader(httpClient, host),
        deviceId = deviceId,
    )

    // Fires the event notify after a drained cycle that completed uploads (capability `upload-completion-notify`).
    private val notifier = EventNotifier(KtorPushHttpClient(httpClient), host)

    private val platform = IosUrlSessionUploadPlatform(
        log = log,
        discovery = discovery,
        appGroup = LEDGER_APP_GROUP,
        sessionIdentifier = SESSION_IDENTIFIER,
        useBackgroundSession = useBackgroundSession,
        // Precise stranded-row reconciliation: the ledger's current REQUESTED keys.
        pendingKeys = { ledgerBackend.pendingResources().map { it.key }.toSet() },
        // A slot just freed → top up (single-flight in the pump serialises it).
        onTerminal = { scope.launch { pump.onUploadCompleted() } },
    )

    private val pump = BackgroundUploadPump(
        runCycle = { runCycle() },
        scheduler = scheduler,
        log = log,
    )

    // The OS completion handler from `handleEventsForBackgroundURLSession`, held until the session
    // reports all events delivered (then invoked exactly once).
    private var backgroundEventsCompletion: (() -> Unit)? = null

    init {
        platform.onBackgroundEventsFinished = {
            backgroundEventsCompletion?.invoke()
            backgroundEventsCompletion = null
            scope.launch { pump.onSessionEvents() }
        }
    }

    /** One upload cycle: fresh config each time, then the shared [UploadCycle] over the URLSession platform. */
    private suspend fun runCycle(): CycleResult {
        log.i { "url-session runCycle: enter" }
        configSource.reload()
        val config = buildUploadConfig(configSource.config.value?.eventId, host) ?: run {
            log.i { "url-session cycle skipped — no joined event / host (eventId=${configSource.config.value?.eventId}, host=$host)" }
            return CycleResult.COMPLETED
        }
        log.i { "url-session runCycle: config ok (host=${config.host}) — invoking UploadCycle" }
        val engine = SyncEngine(EdgeUploadRequestProvider(config.host, deviceId), ledger)
        val cycleEventId = config.eventId // this cycle's event (config is re-read each cycle)
        val result = UploadCycle(
            engine, ledger, platform, discoveryStore, log,
            // Device manifest from THIS cycle's discovery, PUT after the byte jobs are created; bounded
            // and best-effort (any failure/timeout just retries next cycle). The union reflects the
            // uploads only after this PUT — the same drain point the notify below fires from.
            onDiscovery = { discovery ->
                runCatching {
                    withTimeout(DEVICE_MANIFEST_TIMEOUT_MS) {
                        deviceManifestProducer.produce(
                            eventId = cycleEventId,
                            startDate = null, // whole-library scope (the date filter is deferred)
                            discovered = deviceManifestAssetsFromResources(discovery.resources),
                            removedAssetIds = discovery.removedAssetIds.toSet(),
                            fullEnumeration = discovery.fullEnumeration,
                        )
                    }
                }.onFailure { log.w(it) { "device.json production failed/timed out this cycle" } }
            },
            // Notify the event's members after the manifest PUT — bounded so a hung host can't stall the
            // cycle. Fires only on a fully-drained cycle with >= 1 completion (gated inside UploadCycle).
            onBatchUploaded = {
                runCatching { withTimeout(NOTIFY_TIMEOUT_MS) { notifier.notify(cycleEventId) } }
                    .onFailure { log.w(it) { "event notify failed/timed out this cycle" } }
            },
            // Echo-suppression: never re-upload an asset this device downloaded + imported.
            suppressedAssetIds = suppressedAssetIds,
        ).run()
        log.i { "url-session runCycle: UploadCycle returned $result" }
        return result
    }

    // ---- lifecycle / triggers (forwarded by SnapSyncRoot) ----

    /** On a full photo grant / app start: sweep orphaned staging, run a cycle, arm the heartbeat. */
    fun start() {
        log.i { "url-session controller start()" }
        scope.launch {
            runCatching { platform.sweepStaging() }.onFailure { log.w(it) { "sweepStaging failed" } }
            pump.onForeground()
        }
    }

    /** Foreground entry — pump a cycle (completions drive the rest while open). */
    fun onForeground() {
        log.i { "url-session controller onForeground()" }
        scope.launch { pump.onForeground() }
    }

    /** The `BGProcessingTask` heartbeat handler fired — top up and re-arm. Call [done] when finished. */
    fun onBackgroundTask(done: () -> Unit) {
        scope.launch {
            try {
                pump.onBackgroundTask()
            } finally {
                done()
            }
        }
    }

    /** The OS relaunched us to finish background transfers — hold the completion, let the session drain. */
    fun onBackgroundSessionEvents(completion: () -> Unit) {
        backgroundEventsCompletion = completion
        // Touch the session so it re-attaches and begins delivering its completion callbacks (which
        // fire onBackgroundEventsFinished, invoking `completion` + pumping onSessionEvents).
        platform.reattach()
    }

    /** Disable (access revoked): cancel in-flight transfers + the scheduled heartbeat. */
    fun disable() {
        platform.cancelAll()
        scheduler.cancel()
    }

    /** Leave: cancel everything, then wipe the local ledger + discovery cursor. */
    fun leave() {
        disable()
        scope.launch {
            ledgerBackend.clear()
            discoveryStore.clearToken()
        }
    }
}
