package app.snapsync.ios

import app.snapsync.ports.DeviceIdentity
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.compose.UploaderProcess
import app.snapsync.compose.AlbumLookupFailure
import app.snapsync.ports.AlbumManager
import app.snapsync.compose.UploadPorts
import app.snapsync.compose.uploadCore
import app.snapsync.compose.appUploadDiscovery
import app.snapsync.config.FileBackedConfigStore
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.model.SelectionScope
import app.snapsync.ports.LedgerStore
import app.snapsync.gallery.IosDeviceManifestStore
import app.snapsync.gallery.PhotoKitCandidateSource
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ios.discovery.PhotoKitLibraryChangeTokenRead
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.ios.urlsession.IosBackgroundScheduler
import app.snapsync.ios.urlsession.IosUrlSessionUploadPlatform
import app.snapsync.join.HttpManifestPublisher
import app.snapsync.ports.BackgroundScheduler
import app.snapsync.ports.CycleResult
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.ports.SuppressionSource
import app.snapsync.feature.upload.AppUploadEvents
import app.snapsync.feature.upload.AppUploadMechanism
import app.snapsync.feature.upload.UploadAdmission
import app.snapsync.feature.upload.WalkOutcome
import app.snapsync.logging.appMarketingVersion
import app.snapsync.logging.SentryDiagnosticsReporter
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

/**
 * The app's uploader — the app-process analogue of `UploadExtensionRoot`, driving the shared `:domain`
 * `feature/upload` `UploadCycle` over a background `URLSession` instead of the PhotoKit OS-job queue. Assembled
 * lazily by [SnapSyncRoot] on every OS: its units are the composed core's tail units ② and ③, and its cycle's entry
 * gate withholds when the app may not create (capability `background-upload`). On iOS ≥26.1 under a full grant it runs
 * **beside** the extension, both writing the one App-Group ledger — every write a guarded single transaction, and an
 * overlap a duplicate upload of the same object, never a loss (decision record `changes/both-uploaders-active`).
 *
 * It is a **mechanism**, and holds no trigger and no OS completion handler: which wake runs what, how the heartbeat is
 * re-armed and how a wake's handler is held are the core's tail runner and inbound port (decision record
 * `changes/own-work-per-wake`). What the transport observes — a recorded completion, the session's drain report —
 * reaches the core through [events], each one call.
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
    private val deviceIdentity: DeviceIdentity,
    private val host: String,
    private val log: Logger,
    // The shared Darwin HTTP client — used for the in-cycle device-manifest PUT and the event-notify POST.
    private val httpClient: HttpClient,
    // The device token (capability `privacy-security`), read PER REQUEST so a background renewal is
    // picked up on the next retry. This tier uploads from the APP process, which is also the process that
    // can attest — so unlike the extension, it is never stuck with a token it cannot refresh.
    private val token: suspend () -> String?,
    private val freshToken: suspend () -> String?,
    // Echo-suppression (capability `receiving-photos`): the `assetId`s of foreign assets this device
    // downloaded + imported. Read once per cycle so an imported foreign asset is never re-uploaded (the
    // echo) — essential now that this tier writes the device manifest and so appears in the union.
    private val suppression: SuppressionSource,
    // Denylisted-album membership (capability `photo-sharing`) — the SAME port the PhotoKit tier
    // gets. Both tiers funnel through the shared UploadCycle, so the policy must be supplied on both or the
    // 18–26.0 tier would happily upload the WhatsApp album the ≥26.1 tier refuses. Takes the cutoff, which
    // scopes the album member fetch.
    //
    // Read through the album port; this tier admits on doubt, exactly as the app graph's status total does
    // (the shared composition builds both reads from the same port and the same declared answer).
    private val albumManager: AlbumManager,
    // The app graph's per-cycle answers this engine only FORWARDS — see [AppGraphReads].
    private val graph: AppGraphReads,
    // Event-album placement (capability `event-album`): the shared coordinator, so this app-tier
    // (iOS 18–26.0) adds this cycle's completed own photos to the event album. The membership's
    // opt-in is applied by the cycle, which reads it from the gate; the `assetId` denormalization
    // is `uploadCore`'s shared translation.
    private val albumCoordinator: AlbumCoordinator,
    // What the transport tells the composed core: a recorded completion, and the session's drain report. A
    // provider, resolved when the transport calls, because the core is composed after this adapter exists.
    private val events: () -> AppUploadEvents,
) : AppUploadMechanism {
    companion object {
        const val SESSION_IDENTIFIER = "app.snapsync.upload.session"
        const val HEARTBEAT_TASK_IDENTIFIER = "app.snapsync.upload.heartbeat"
    }

    // The app process's discovery binding: the walk behind the walk memo (capability `photo-sharing`, "An unchanged
    // library is answered from the walk memo"). The extension binds its walk bare — it never holds a memo.
    private val discovery = appUploadDiscovery(
        walk = IosDiscovery(log, PhotoKitCandidateSource()),
        changeToken = PhotoKitLibraryChangeTokenRead(),
        grant = PhotoGrantRead(::currentPhotoPermission),
        log = log,
    )

    /** The `BGProcessingTask` heartbeat the core's tail runner re-arms and a disarm cancels. */
    override val heartbeat: BackgroundScheduler =
        IosBackgroundScheduler(log, HEARTBEAT_TASK_IDENTIFIER, requiresNetwork = true)

    private val platform = IosUrlSessionUploadPlatform(
        log = log,
        appGroup = LEDGER_APP_GROUP,
        sessionIdentifier = SESSION_IDENTIFIER,
        // The adapter records terminal outcomes itself, the moment iOS delivers one, through the narrow
        // `TransferRecord` the store satisfies. It reads no other ledger state.
        ledger = ledgerStore,
        // A slot just freed — the completion is recorded already; the core decides whether to top up.
        onTerminal = { events().uploadCompleted() },
        // The session delivered every event it had: the relaunch's own work is done.
        onEventsFinished = { events().eventsDrained() },
    )

    /**
     * The cycle — assembled by the SHARED composition `uploadCore` (`docs/architecture.md`, "One
     * shared composition"): this controller supplies only its ports and platform reads; the
     * entry-gate translation, the device-manifest producer (capability `photo-sharing` — on this tier the APP is
     * its sole writer, and without the PUT this tier's uploads would never appear in the event union), and the engine
     * wiring are the same code the ≥26.1 extension and the world harness run.
     *
     * The entry gate reads the **three-state** `ConfigReader`, never `configSource.config` — that
     * port's own KDoc says it *"cannot express unreadable"*, and this tier once read it anyway: a
     * failed Keychain read arrived as `null`, which this tier treated as a leave of a device that never
     * left (capability `join-event`).
     *
     * Long-lived (one per process, like this controller): each unit re-reads the membership, so a join, leave, or
     * switch takes effect on the next unit.
     */
    private val cycle: UploadCycle by lazy {
        uploadCore(
            scope,
            UploadPorts(
                appVersion = appMarketingVersion(),
                diagnosticsReporter = SentryDiagnosticsReporter(),
                process = UploaderProcess.App(graph.admission, PhotoGrantRead { graph.photoAccess.permission.value }),
                config = configSource,
                // Resolved per probe/use, never held: an unresolvable Keychain id must skip the
                // cycle cleanly, not throw out of whatever first touches it (see [deviceIdentity]).
                deviceIdentity = deviceIdentity,
                host = host,
                ledger = ledgerStore,
                transfer = platform,
                discovery = discovery,
                selectionScope = graph.selectionScope,
                // The device manifest PUT goes through the generic `HttpManifestPublisher` (the former
                // app-local `IosEnrollment` copy is dead — one uploader serves all).
                manifestStore = IosDeviceManifestStore(),
                manifestPublisher = HttpManifestPublisher(httpClient, host),
                suppression = suppression,
                // Denylisted-album membership (capability `photo-sharing`), scoped by the
                // cutoff — the SAME wrapper the own-device status total gets (admit-on-doubt).
                albumManager = albumManager,
                albumLookupFailure = AlbumLookupFailure.AdmitOnDoubt,
                albumCoordinator = albumCoordinator,
                token = token,
                freshToken = freshToken,
                log = log,
            ),
        )
    }

    // ---- the AppUploadMechanism seam (capability `background-upload`) ----
    // Which unit runs when, and how a stop reaches it, are the core's tail runner's. This class supplies only this
    // tier's MECHANISM.

    override suspend fun topUp(stopRequested: () -> Boolean): CycleResult =
        log.invocation("url-session.topUp", result = { "$it" }) { cycle.topUp(stopRequested) }

    override suspend fun walkAndPublish(stopRequested: () -> Boolean): WalkOutcome =
        log.invocation("url-session.walkAndPublish", result = { "$it" }) { cycle.walkAndPublish(stopRequested) }

    /**
     * Cancel every in-flight transfer and delete its staged file — a **leave** only (a switch leaves first). A
     * cancelled task's `-999` completion is recorded by the delegate's guarded write, which matches no row once
     * the leave has cleared the ledger.
     */
    override suspend fun cancelTransfers() = log.invocation("url-session.cancelTransfers") {
        platform.cancelTransfers()
    }

    /** Touch the session so it re-attaches and delivers its completions, then its drain report. */
    override fun reattach() = log.invocation("url-session.reattach") {
        platform.reattach()
    }
}

/**
 * The per-cycle answers the app graph derives for this engine and the engine only **forwards** — each is decided in
 * tested `:domain` code, never branched on here, because this module is wiring-only by project rule. Bundled because
 * they share that one property, and because each is required with no default.
 */
class AppGraphReads(
    // Current photo access — the grant read the cycle's entry gate records.
    val photoAccess: PhotoAccessStatusSource,
    // What upload discovery may read (capability `photo-access`): the walk-vs-snapshot decision. A
    // composition that forgot it would walk the library under a partial grant, where the selection IS the scope.
    val selectionScope: () -> SelectionScope,
    // Whether this engine's cycle may create now (capability `background-upload`): any usable grant, unless the rig
    // switched the app off. Every unit reaches this engine; its entry gate withholds otherwise.
    val admission: () -> UploadAdmission,
)
