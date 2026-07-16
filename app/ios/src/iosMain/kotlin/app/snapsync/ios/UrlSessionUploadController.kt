package app.snapsync.ios

import app.snapsync.config.KeychainConfigStore
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.SyncEngine
import app.snapsync.gallery.Contribution
import app.snapsync.gallery.DeviceManifestProducer
import app.snapsync.gallery.IosDeviceManifestStore
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
import app.snapsync.gallery.deviceManifestAssetsFromResources
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ios.discovery.IosDiscoveryStore
import app.snapsync.ios.urlsession.IosBackgroundScheduler
import app.snapsync.ios.urlsession.IosUrlSessionUploadPlatform
import app.snapsync.membership.ExtensionReconciler
import app.snapsync.membership.HttpDeviceFilesSource
import app.snapsync.membership.IosJoinedEventMarker
import app.snapsync.push.EventNotifier
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.push.PushReceiver
import app.snapsync.upload.BackgroundUploadPump
import app.snapsync.upload.CycleResult
import app.snapsync.upload.UploadCycle
import app.snapsync.upload.UploadPushReceiver
import app.snapsync.upload.UploadProducer
import app.snapsync.upload.buildUploadConfig
import app.snapsync.uploadurl.EdgeUploadRequestProvider
import app.snapsync.logging.invocation
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
 * [BackgroundUploadPump] is the in-app reimplementation of the OS scheduler; its triggers are forwarded
 * from [SnapSyncRoot] (foreground, `BGProcessingTask`, background-session relaunch, per-completion) plus
 * a producer start and a **silent push** for the active event, the latter via [pushReceiver]. Config is
 * re-read each cycle so a newly-joined event takes effect — including the membership's `Contribution`,
 * which is what makes a download-only membership's cycle decline (capability `upload-lifecycle`).
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
    // The device token (capability `device-attestation`), read PER REQUEST so a background renewal is
    // picked up on the next retry. This tier uploads from the APP process, which is also the process that
    // can attest — so unlike the extension, it is never stuck with a token it cannot refresh.
    private val token: suspend () -> String?,
    // Echo-suppression (capability `photo-download`): the `assetId`s of foreign assets this device
    // downloaded + imported. Read once per cycle so an imported foreign asset is never re-uploaded (the
    // echo) — essential now that this tier writes the device manifest and so appears in the union.
    private val suppressedAssetIds: suspend () -> Set<String>,
    // Denylisted-album membership (capability `photo-selection-policy`) — the SAME port the PhotoKit tier
    // gets. Both tiers funnel through the shared UploadCycle, so the policy must be supplied on both or the
    // 18–26.0 tier would happily upload the WhatsApp album the ≥26.1 tier refuses. Takes the cutoff, which
    // scopes the album member fetch.
    private val albumExcludedAssetIds: suspend (String) -> Set<String> = { emptySet() },
    // False on the dev/test-forced (simulator) path — the sim can't run a background NSURLSession.
    private val useBackgroundSession: Boolean = true,
    // Fired after each in-process pump cycle so foreground upload status refreshes live (the app-driven
    // analogue of the PhotoKit extension's cross-process liveness ding — here an in-process re-read).
    private val onCycleComplete: suspend () -> Unit = {},
    // Event-album placement (capability `event-album`): fired with the `assetId`s that completed this
    // cycle so this app-tier (iOS 18–26.0) adds them to the event album. Bound by SnapSyncRoot to the
    // album coordinator (gated on the opt-in); default no-op.
    private val albumPlacement: suspend (Set<String>) -> Unit = {},
) : UploadProducer {
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

    // Re-join reconciliation (capability `event-rejoin-reconciliation`) — the SAME reconciler the ≥26.1
    // extension runs, now on this tier too. It is marker-gated, so a settled join costs nothing; on a
    // (re)join it `resetTo`s the ledger to exactly the device's stored files and clears the discovery
    // cursor, so a switch / leave-then-rejoin / delete-and-reinstall re-uploads NOTHING already in the
    // device's byte partition. This tier shipped without it — that is why a reinstall re-uploaded the whole
    // post-cutoff library — and it is reached from inside `UploadCycle`, so no future tier can omit it.
    private val reconciler = ExtensionReconciler(
        files = HttpDeviceFilesSource(httpClient, host),
        ledger = ledgerBackend,
        marker = IosJoinedEventMarker(),
        deviceId = deviceId,
        // Force a full re-enumeration on a re-join: the App-Group cursor survives an app upgrade, so a
        // settled cursor would scan incrementally and find nothing. The seeded ledger dedups, so this
        // re-uploads nothing already stored — it only re-discovers genuinely-unstored work.
        clearDiscoveryCursor = { discoveryStore.clearToken() },
        log = log,
    )

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

    /**
     * The upload arm's silent-push receiver (capability `ios-url-session-upload`) — composed **here**, the
     * tier's own composition root, because this is where the pump lives; the pump itself stays private.
     * [SnapSyncRoot] fans one push out to this and the download arm's receiver, so neither arm learns about
     * the other.
     *
     * The active-event guard is inside `UploadPushReceiver` (a tested capability), not here — this property
     * is wiring, per the project's hard rule.
     */
    val pushReceiver: PushReceiver by lazy {
        UploadPushReceiver(
            activeEventId = { configSource.config.value?.eventId },
            pump = pump,
        )
    }

    private val pump = BackgroundUploadPump(
        runCycle = { runCycle() },
        scheduler = scheduler,
        log = log,
        onCycleComplete = onCycleComplete,
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
    private suspend fun runCycle(): CycleResult = log.invocation("url-session.runCycle", result = { "$it" }) {
        configSource.reload()
        // Read the membership once: an unreadable config (including a legacy Keychain item with no cutoff,
        // capability `photo-selection-policy`) means not joined, so this cycle uploads nothing.
        val membership = configSource.config.value
        val config = membership?.let { buildUploadConfig(it.eventId, host) } ?: run {
            // No joined event (never joined, or a leave). No cycle is built — but the reconciler still runs
            // for the no-config case, because that is where a leave clears the `joinedEventId` marker
            // (capability `event-rejoin-reconciliation`), keeping the ledger and cursor intact so a later
            // provision of ANY event dedups against them. The joined case reconciles inside the cycle.
            runCatching { reconciler.reconcile(null) }
                .onFailure { log.w(it) { "leave-side marker clear failed" } }
            log.i { "url-session cycle skipped — no joined event / host (eventId=${membership?.eventId}, host=$host)" }
            return@invocation CycleResult.COMPLETED
        }
        log.i { "url-session runCycle: config ok (host=${config.host}) — invoking UploadCycle" }
        val engine = SyncEngine(EdgeUploadRequestProvider(config.host, deviceId, token), ledger)
        val cycleEventId = config.eventId // this cycle's event (config is re-read each cycle)
        // Per-device capture-date cutoff (photo-selection-policy): scopes the discovery walk, the byte-upload
        // filter, AND the device-manifest projection. Always present. Read fresh with the config.
        val cutoff = membership.minPhotoDate
        // What this membership contributes (capability `photo-selection-policy`): the direction AND the
        // cutoff, in one value. A download-only membership contributes `None`, and the CYCLE declines —
        // the gate is not here. This module is wiring-only and untested by the project's hard rule, which
        // is precisely why the previous direction gate (an invoker-gate, in this shell's tier selection)
        // had no test and let a download-only membership upload the member's camera roll on this tier
        // (capability `upload-lifecycle`).
        val contribution = Contribution.of(membership.direction.includesUpload, cutoff)
        val result = UploadCycle(
            engine, ledger, platform, discoveryStore, log,
            // Re-join reconciliation (capability `event-rejoin-reconciliation`): marker-gated, runs BEFORE
            // any upload job is created. A settled join is a free no-op; a (re)join seeds already-stored
            // resources COMPLETED so nothing already contributed re-uploads. A failed/timed-out listing
            // returns false and defers the whole cycle — no jobs, marker unset, retried next cycle.
            reconcile = { reconciler.reconcile(cycleEventId) },
            // Device manifest from THIS cycle's discovery, PUT after the byte jobs are created; bounded
            // and best-effort (any failure/timeout just retries next cycle). The union reflects the
            // uploads only after this PUT — the same drain point the notify below fires from.
            onDiscovery = { discovery ->
                runCatching {
                    withTimeout(DEVICE_MANIFEST_TIMEOUT_MS) {
                        deviceManifestProducer.produce(
                            eventId = cycleEventId,
                            startDate = cutoff, // per-device capture-date cutoff (photo-selection-policy)
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
            // Denylisted-album membership (capability `photo-selection-policy`), scoped by the cutoff.
            albumExcludedAssetIds = { albumExcludedAssetIds(cutoff) },
            contribution = contribution,
            // Event album (capability `event-album`): add this cycle's completed own photos to the album.
            placeInAlbum = albumPlacement,
        ).run()
        result
    }

    // ---- the UploadProducer seam (capability `upload-lifecycle`) ----
    // The lifecycle DECISION — which verb on which transition — lives in the tested `UploadArm`
    // (`:capability:upload`). This class supplies only this tier's MECHANISM.

    /**
     * Begin/resume uploading: sweep staging temp files orphaned by a prior killed process, **arm the
     * heartbeat**, then pump a cycle.
     *
     * `pump.onStart()` (not `onForeground()`) is what arms the heartbeat: it is the only trigger whose
     * re-arm is unconditional, and therefore the only one that can submit the FIRST `BGProcessingTask`.
     * Every other re-arm path presupposes one already exists, so before this the tier's cold-start kick
     * for "new photos captured while the app is closed" never existed at all. The re-arm *policy* lives in
     * the tested pump, not here — this shell is wiring-only.
     */
    override suspend fun start() = log.invocation("url-session.start") {
        runCatching { platform.sweepStaging() }.onFailure { log.w(it) { "sweepStaging failed" } }
        pump.onStart()
    }

    /** Foreground entry — pump a cycle (completions drive the rest while open). */
    fun onForeground() {
        scope.launch { log.invocation("url-session.onForeground") { pump.onForeground() } }
    }

    /** The `BGProcessingTask` heartbeat handler fired — top up and re-arm. Call [done] when finished. */
    fun onBackgroundTask(done: () -> Unit) {
        scope.launch {
            try {
                log.invocation("url-session.onBackgroundTask") { pump.onBackgroundTask() }
            } finally {
                done()
            }
        }
    }

    /** The OS relaunched us to finish background transfers — hold the completion, let the session drain. */
    fun onBackgroundSessionEvents(completion: () -> Unit) = log.invocation("url-session.onBackgroundSessionEvents") {
        backgroundEventsCompletion = completion
        // Touch the session so it re-attaches and begins delivering its completion callbacks (which
        // fire onBackgroundEventsFinished, invoking `completion` + pumping onSessionEvents).
        platform.reattach()
    }

    /**
     * Stop uploading (access revoked, a download-only membership, or a leave) — the `stop()` half of the
     * [UploadProducer] seam. Cancels in-flight transfers and the scheduled heartbeat, and **destroys no
     * durable state**: the ledger and the discovery cursor are left intact.
     *
     * There is deliberately **no** destructive counterpart. The ledger is device-global dedup state — its
     * key is the bare filename with no event scoping, and leaving an event does not remove this device's
     * bytes from its storage partition — so a `COMPLETED` row stays *true* across a leave, a switch, and a
     * re-join (`sync-ledger`, "Event-independent key"). Wiping it would force a re-upload of everything
     * already stored on the next join, which is exactly what the old `leave()` did. Only a triggered
     * reconciliation's `resetTo` ever re-baselines the ledger, from the authoritative device listing.
     *
     * No `clearRequested` recovery is needed here either: stranded `REQUESTED` rows are already reconciled
     * precisely from `getAllTasks` (see `fetchAckJobs`) — that is this tier's D5.
     */
    override suspend fun stop() = log.invocation("url-session.stop") {
        platform.cancelAll()
        scheduler.cancel()
    }
}
