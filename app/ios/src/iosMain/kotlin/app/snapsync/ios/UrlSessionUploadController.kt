package app.snapsync.ios

import app.snapsync.config.KeychainConfigStore
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.ports.LedgerStore
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.feature.upload.SyncEngine
import app.snapsync.model.Contribution
import app.snapsync.gallery.DeviceManifestProducer
import app.snapsync.gallery.IosDeviceManifestStore
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
import app.snapsync.model.deviceManifestAssetsFromResources
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ios.discovery.IosDiscoveryStore
import app.snapsync.ios.urlsession.IosBackgroundScheduler
import app.snapsync.ios.urlsession.IosUrlSessionUploadPlatform
import app.snapsync.feature.upload.ExtensionReconciler
import app.snapsync.membership.HttpDeviceFilesSource
import app.snapsync.membership.IosJoinedEventMarker
import app.snapsync.push.EventNotifier
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.ports.PushReceiver
import app.snapsync.feature.upload.BackgroundUploadPump
import app.snapsync.ports.CycleResult
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.ports.ConfigRead
import app.snapsync.ports.KeychainUnavailable
import app.snapsync.feature.upload.CycleGate
import app.snapsync.feature.upload.JoinedMembership
import app.snapsync.feature.upload.cycleGate
import app.snapsync.upload.UploadPushReceiver
import app.snapsync.feature.upload.UploadProducer
import app.snapsync.model.EdgeUploadRequestProvider
import app.snapsync.model.invocation
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    private val ledgerStore: LedgerStore,
    private val configSource: KeychainConfigStore,
    // Resolved PER CYCLE, not held as a `String`. A held id cannot express "unreadable this cycle": an
    // unresolvable Keychain read then throws out of whatever first touches it instead of skipping
    // cleanly, and this tier is relaunched cold by the OS to deliver background-session events — a path
    // with no first-unlock guarantee (unlike `BGTaskScheduler`, which Apple guarantees waits). Resolving
    // per cycle costs nothing: the identity caches for the process lifetime after its first success.
    private val resolveDeviceId: () -> String,
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
    //
    // **No default.** It used to carry `{ emptySet() }` while this comment argued it must never be omitted —
    // the signature permitting exactly what the prose forbade. `SnapSyncRoot` did pass it, so the property
    // held by diligence rather than by the compiler, which is what the same comment says failed last time.
    private val albumExcludedAssetIds: suspend (String) -> Set<String>,
    // False on the dev/test-forced (simulator) path — the sim can't run a background NSURLSession.
    private val useBackgroundSession: Boolean = true,
    // Fired after each in-process pump cycle so foreground upload status refreshes live (the app-driven
    // analogue of the PhotoKit extension's cross-process liveness ding — here an in-process re-read).
    private val onCycleComplete: suspend () -> Unit = {},
    // Event-album placement (capability `event-album`): fired with this cycle's event and the `assetId`s
    // that completed, so this app-tier (iOS 18–26.0) adds them to the event album. Bound by SnapSyncRoot to
    // the album coordinator. The membership's opt-in is applied by the cycle, which reads it from the gate.
    private val albumPlacement: suspend (String, Set<String>) -> Unit,
) : UploadProducer {
    companion object {
        const val SESSION_IDENTIFIER = "app.snapsync.upload.session"
        const val HEARTBEAT_TASK_IDENTIFIER = "app.snapsync.upload.heartbeat"
    }

    private val ledger = LedgerWriter(ledgerStore)
    private val discovery = IosDiscovery(log, PhotoLibraryResourceEnumerator())
    private val discoveryStore = IosDiscoveryStore()
    private val scheduler = IosBackgroundScheduler(log, HEARTBEAT_TASK_IDENTIFIER)

    // The per-event device manifest (capability `device-manifest`) — on this tier the APP is its sole
    // writer (no extension process). Produced from the cycle's OWN discovery (no second enumeration),
    // PUT synchronously in-cycle and bounded by `withTimeout`. Without it this tier's uploads would never
    // appear in the event union (union = manifest ∩ stored bytes), so no other device could download them.
    // `by lazy`, because the device id is resolved per cycle now: first use is inside a cycle whose gate
    // already probed the identity, so the resolve here cannot be the one that fails.
    private val deviceManifestProducer: DeviceManifestProducer by lazy {
        DeviceManifestProducer(
            store = IosDeviceManifestStore(),
            uploader = IosEnrollment(httpClient, host),
            deviceId = resolveDeviceId(),
        )
    }

    // Fires the event notify after a drained cycle that completed uploads (capability `upload-completion-notify`).
    private val notifier = EventNotifier(KtorPushHttpClient(httpClient), host)

    // Re-join reconciliation (capability `event-rejoin-reconciliation`) — the SAME reconciler the ≥26.1
    // extension runs, now on this tier too. It is marker-gated, so a settled join costs nothing; on a
    // (re)join it `resetTo`s the ledger to exactly the device's stored files and clears the discovery
    // cursor, so a switch / leave-then-rejoin / delete-and-reinstall re-uploads NOTHING already in the
    // device's byte partition. This tier shipped without it — that is why a reinstall re-uploaded the whole
    // post-cutoff library — and it is reached from inside `UploadCycle`, so no future tier can omit it.
    private val reconciler: ExtensionReconciler by lazy {
        ExtensionReconciler(
        files = HttpDeviceFilesSource(httpClient, host),
        ledger = ledgerStore,
        marker = IosJoinedEventMarker(),
        deviceId = resolveDeviceId(),
        // Force a full re-enumeration on a re-join: the App-Group cursor survives an app upgrade, so a
        // settled cursor would scan incrementally and find nothing. The seeded ledger dedups, so this
        // re-uploads nothing already stored — it only re-discovers genuinely-unstored work.
        clearDiscoveryCursor = { discoveryStore.clearToken() },
        log = log,
        )
    }

    private val platform = IosUrlSessionUploadPlatform(
        log = log,
        discovery = discovery,
        appGroup = LEDGER_APP_GROUP,
        sessionIdentifier = SESSION_IDENTIFIER,
        useBackgroundSession = useBackgroundSession,
        // Precise stranded-row reconciliation: the ledger's current REQUESTED keys.
        pendingKeys = { ledgerStore.pendingResources().map { it.key }.toSet() },
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

    /**
     * The membership read, translated into the shared vocabulary — this root's only contribution to the
     * decision, and it is a translation, not a decision (capability `upload-lifecycle`).
     *
     * It reads the **three-state** `ConfigReader`, never `configSource.config` — that port's own KDoc says
     * it *"cannot express unreadable"* and is *"fatal for the reconciler"*, and this tier read it anyway:
     * a failed Keychain read arrived as `null`, which this tier treated as a leave and used to clear the
     * `joinedEventId` marker of a device that never left (capability `event-link`).
     */
    private fun readGate(): CycleGate {
        // Re-read each cycle: a newly-joined event must take effect without a relaunch, and the Keychain is
        // written by paths this process does not observe.
        configSource.reload()
        val read = configSource.read()
        // The identity probe — an unresolvable id is "I could not look", never "no id", so it belongs on
        // the unreadable side of the roll-up. This tier had no probe at all: an unresolvable id threw out
        // of the cycle instead of skipping it.
        val idReadable = runCatching { resolveDeviceId() }
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
            host = host,
            skipDetail = "protected data unavailable (config status=" +
                "${(read as? ConfigRead.Unavailable)?.status}, deviceId readable=$idReadable)",
        )
    }

    /**
     * The cycle. Long-lived (one per process, like this controller): it re-reads the membership itself on
     * each `run()` via [readGate], so a join, leave, or switch takes effect on the next cycle.
     */
    private val cycle: UploadCycle by lazy {
        UploadCycle(
            readGate = ::readGate,
            engineFor = { config ->
                SyncEngine(EdgeUploadRequestProvider(config.host, resolveDeviceId(), token), ledger)
            },
            ledger = ledger,
            platform = platform,
            store = discoveryStore,
            log = log,
            // Re-join reconciliation (capability `event-rejoin-reconciliation`): marker-gated, runs BEFORE
            // any upload job is created. `null` is the leave side — the cycle decides which, because
            // deciding it here is what shipped a false leave on this tier.
            reconcile = { eventId -> reconciler.reconcile(eventId) },
            // Device manifest from THIS cycle's discovery, PUT after the byte jobs are created. Without it
            // this tier's uploads never appear in the event union (union = manifest ∩ stored bytes), so no
            // other device could download them. Bounding is the cycle's now, not this root's.
            onDiscovery = { eventId, cutoff, discovery ->
                deviceManifestProducer.produce(
                    eventId = eventId,
                    startDate = cutoff, // per-device capture-date cutoff (photo-selection-policy)
                    discovered = deviceManifestAssetsFromResources(discovery.resources),
                    removedAssetIds = discovery.removedAssetIds.toSet(),
                    fullEnumeration = discovery.fullEnumeration,
                )
            },
            // Echo-suppression: never re-upload an asset this device downloaded + imported.
            suppressedAssetIds = suppressedAssetIds,
            // Denylisted-album membership (capability `photo-selection-policy`), scoped by the cutoff.
            albumExcludedAssetIds = { cutoff -> albumExcludedAssetIds(cutoff) },
            onBatchUploaded = { eventId -> notifier.notify(eventId) },
            // Event album (capability `event-album`): the cycle applies the membership's opt-in, which
            // arrived with the gate.
            placeInAlbum = { eventId, assetIds -> albumPlacement(eventId, assetIds) },
        )
    }

    /** One upload cycle. The membership read, the gate, and the assembly are all the cycle's. */
    private suspend fun runCycle(): CycleResult =
        log.invocation("url-session.runCycle", result = { "$it" }) { cycle.run() }

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
