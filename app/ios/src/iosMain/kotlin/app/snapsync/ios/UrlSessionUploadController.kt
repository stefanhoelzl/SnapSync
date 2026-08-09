package app.snapsync.ios

import app.snapsync.model.CaptureCutoff
import app.snapsync.compose.UploadPorts
import app.snapsync.compose.uploadCore
import app.snapsync.config.FileBackedConfigStore
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.model.SelectionScope
import app.snapsync.ports.OsReceipt
import app.snapsync.ports.ReceiptDeadlines
import app.snapsync.ports.LedgerStore
import app.snapsync.gallery.IosDeviceManifestStore
import app.snapsync.gallery.PhotoKitCandidateSource
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ios.discovery.IosDiscoveryStore
import app.snapsync.ios.urlsession.IosBackgroundScheduler
import app.snapsync.ios.urlsession.IosUrlSessionUploadPlatform
import app.snapsync.join.HttpEnrollment
import app.snapsync.membership.HttpDeviceFilesSource
import app.snapsync.membership.IosJoinedEventMarker
import app.snapsync.feature.push.EventNotifier
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.ports.PushReceiver
import app.snapsync.feature.upload.BackgroundUploadPump
import app.snapsync.ports.CycleResult
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.ports.SuppressionSource
import app.snapsync.feature.upload.UploadPushReceiver
import app.snapsync.feature.upload.UploadProducer
import app.snapsync.logging.IosLogScope
import app.snapsync.logging.SentryDiagnosticsReporter
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app-driven (iOS 18–26.0) upload tier's composition root — the app-process analogue of
 * `UploadExtensionRoot`, but driving the shared `:domain` `feature/upload` `UploadCycle` over a background
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
    private val configSource: FileBackedConfigStore,
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
    private val suppression: SuppressionSource,
    // Denylisted-album membership (capability `photo-selection-policy`) — the SAME port the PhotoKit tier
    // gets. Both tiers funnel through the shared UploadCycle, so the policy must be supplied on both or the
    // 18–26.0 tier would happily upload the WhatsApp album the ≥26.1 tier refuses. Takes the cutoff, which
    // scopes the album member fetch.
    //
    // **No default.** It used to carry `{ emptySet() }` while this comment argued it must never be omitted —
    // the signature permitting exactly what the prose forbade. `SnapSyncRoot` did pass it, so the property
    // held by diligence rather than by the compiler, which is what the same comment says failed last time.
    private val albumExcludedAssetIds: suspend (CaptureCutoff) -> Set<String>,
    // What upload discovery may read (capability `limited-photo-access`): the app graph's derived
    // walk-vs-snapshot decision. **No default** for the same reason as the album lookup above — this
    // tier serves LIMITED memberships, and a composition that forgot the scope would walk the library
    // under a partial grant, where the selection IS the scope: it would discover photos the member never
    // chose to share. (Not the alert: that is armed per out-of-scope library change, not per read.)
    private val selectionScope: () -> SelectionScope,
    // Fired after each in-process pump cycle so foreground upload status refreshes live (the app-driven
    // analogue of the PhotoKit extension's cross-process liveness ding — here an in-process re-read).
    private val onCycleComplete: suspend () -> Unit = {},
    // Event-album placement (capability `event-album`): the shared coordinator, so this app-tier
    // (iOS 18–26.0) adds this cycle's completed own photos to the event album. The membership's
    // opt-in is applied by the cycle, which reads it from the gate; the `assetId` denormalization
    // is `uploadCore`'s shared translation.
    private val albumCoordinator: AlbumCoordinator,
) : UploadProducer {
    companion object {
        const val SESSION_IDENTIFIER = "app.snapsync.upload.session"
        const val HEARTBEAT_TASK_IDENTIFIER = "app.snapsync.upload.heartbeat"
    }

    private val discovery = IosDiscovery(log, PhotoKitCandidateSource())
    private val discoveryStore = IosDiscoveryStore()
    private val scheduler = IosBackgroundScheduler(log, HEARTBEAT_TASK_IDENTIFIER)

    // Fires the event notify after a drained cycle that completed uploads (capability
    // `upload-completion-notify`; `:domain` feature/push since the migration finale re-homed it).
    // Root-constructed over this tier's HTTP client; `uploadCore` takes the notify as a stated lambda.
    private val notifier = EventNotifier(KtorPushHttpClient(httpClient), host)

    private val platform = IosUrlSessionUploadPlatform(
        log = log,
        discovery = discovery,
        appGroup = LEDGER_APP_GROUP,
        sessionIdentifier = SESSION_IDENTIFIER,
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
        logScope = IosLogScope,
        onCycleComplete = onCycleComplete,
    )

    // The OS completion handler from `handleEventsForBackgroundURLSession`, held until the session
    // reports all events delivered (then invoked exactly once).
    private var backgroundEventsCompletion: (() -> Unit)? = null

    init {
        platform.onBackgroundEventsFinished = {
            val completion = backgroundEventsCompletion
            backgroundEventsCompletion = null
            scope.launch {
                // The session drained ITS events; the cycle those events feed has not run yet. Releasing
                // the OS handler here — which is what this did — reported work that was merely queued
                // (capability `ios-app-shell`). With no handler (a foreground drain) the pump still runs.
                if (completion == null) {
                    pump.onSessionEvents()
                } else {
                    OsReceipt(
                        entryPoint = "url-session.onBackgroundSessionEvents",
                        deadline = ReceiptDeadlines.URL_SESSION_EVENTS,
                        release = completion,
                    ).heldFor { pump.onSessionEvents() }
                }
            }
        }
    }

    /**
     * The cycle — assembled by the SHARED composition `uploadCore` (spec `module-architecture`, "One
     * shared composition"): this controller supplies only its ports and platform reads; the
     * entry-gate translation, the reconciler (capability `event-rejoin-reconciliation` — reached
     * from inside `UploadCycle`, so no future tier can omit it), the device-manifest producer
     * (capability `device-manifest` — on this tier the APP is its sole writer, and without the PUT
     * this tier's uploads would never appear in the event union), and the engine wiring are the
     * same code the ≥26.1 extension and the world harness run.
     *
     * The entry gate reads the **three-state** `ConfigReader`, never `configSource.config` — that
     * port's own KDoc says it *"cannot express unreadable"*, and this tier once read it anyway: a
     * failed Keychain read arrived as `null`, which this tier treated as a leave and used to clear
     * the `joinedEventId` marker of a device that never left (capability `event-link`). The gate is
     * port-pure: this tier's former per-cycle `configSource.reload()` StateFlow refresh is gone —
     * see `uploadCore.readGate`'s decision comment (`establish-shared-composition` D1); the
     * StateFlow's unlock repair lives in `SnapSyncRoot`'s protected-data hook.
     *
     * Long-lived (one per process, like this controller): the cycle re-reads the membership on each
     * `run()`, so a join, leave, or switch takes effect on the next cycle.
     */
    private val cycle: UploadCycle by lazy {
        uploadCore(
            scope,
            UploadPorts(
                diagnosticsReporter = SentryDiagnosticsReporter(),
                config = configSource,
                // Resolved per probe/use, never held: an unresolvable Keychain id must skip the
                // cycle cleanly, not throw out of whatever first touches it (see [resolveDeviceId]).
                deviceId = resolveDeviceId,
                host = { host },
                ledger = ledgerStore,
                transfer = platform,
                selectionScope = selectionScope,
                discoveryStore = discoveryStore,
                // Re-join reconciliation seed: the device's stored-file listing over the shared
                // Darwin client. This tier shipped without a reconciler once — that is why a
                // reinstall re-uploaded the whole post-cutoff library.
                deviceFiles = HttpDeviceFilesSource(httpClient, host),
                joinedMarker = IosJoinedEventMarker(),
                // The device manifest PUT goes through the generic `HttpEnrollment` (the former
                // app-local `IosEnrollment` copy is dead — one uploader serves all).
                manifestStore = IosDeviceManifestStore(),
                enrollment = HttpEnrollment(httpClient, host),
                suppression = suppression,
                // Denylisted-album membership (capability `photo-selection-policy`), scoped by the
                // cutoff — the SAME wrapper the own-device status total gets (admit-on-doubt).
                albumExcludedAssetIds = { cutoff -> albumExcludedAssetIds(cutoff) },
                albumCoordinator = albumCoordinator,
                token = token,
                onBatchUploaded = { eventId -> notifier.notify(eventId) },
                log = log,
            ),
        )
    }

    /** One upload cycle. The membership read, the gate, and the assembly are all the cycle's. */
    private suspend fun runCycle(): CycleResult =
        log.invocation("url-session.runCycle", result = { "$it" }) { cycle.run() }

    // ---- the UploadProducer seam (capability `upload-lifecycle`) ----
    // The lifecycle DECISION — which verb on which transition — lives in the tested `UploadArm`
    // (`:domain` `feature/upload`). This class supplies only this tier's MECHANISM.

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

    /**
     * Foreground entry — pump a cycle (completions drive the rest while open).
     *
     * Awaited, not launched: its caller is a `flow/` trigger whose own caller reports completion to the
     * OS (law "A trigger flow never outlives its own run"). A `scope.launch` here made that report a
     * statement about work that had not started.
     */
    suspend fun onForeground() {
        log.invocation("url-session.onForeground") { pump.onForeground() }
    }

    /** The photo selection changed under a partial grant — pump a cycle over the new snapshot. */
    suspend fun onSelectionChanged() {
        log.invocation("url-session.onSelectionChanged") { pump.onSelectionChanged() }
    }

    /** The `BGProcessingTask` heartbeat handler fired — top up and re-arm. Call [done] when finished. */
    fun onBackgroundTask(done: () -> Unit) {
        scope.launch {
            // Already awaited its work before this change (the one receipt in the app that was correct);
            // routed through [OsReceipt] anyway so every OS handler in the app is released by the same
            // bounded, release-exactly-once path rather than by four hand-written `finally`s.
            OsReceipt(
                entryPoint = "url-session.onBackgroundTask",
                deadline = ReceiptDeadlines.BACKGROUND_TASK,
                release = done,
            ).heldFor {
                log.invocation("url-session.onBackgroundTask") { pump.onBackgroundTask() }
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
