package app.snapsync.compose

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.creation.CreateEvent
import app.snapsync.feature.creation.EventCreator
import app.snapsync.feature.creation.HeadlessCreate
import app.snapsync.feature.creation.LaunchEnvMembership
import app.snapsync.feature.creation.MutableCreationStatusSource
import app.snapsync.feature.diagnostics.CollectDiagnosticDump
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.DownloadPushReceiver
import app.snapsync.feature.download.DownloadStatusSource
import app.snapsync.feature.download.QueuedPhotoDownloadJobs
import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.feature.membership.MembershipRefresh
import app.snapsync.feature.membership.toJoinLoad
import app.snapsync.feature.membership.JoinEvent
import app.snapsync.feature.membership.JoinOutcome
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.feature.membership.ManifestDeviceEnroller
import app.snapsync.feature.membership.MutableRenameStatusSource
import app.snapsync.feature.membership.ReconfigureEvent
import app.snapsync.feature.membership.RenameEvent
import app.snapsync.feature.membership.ResetDeviceState
import app.snapsync.feature.status.LedgerBackedSyncStatusSource
import app.snapsync.feature.status.LedgerCounts
import app.snapsync.feature.status.LedgerCountsPoller
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.feature.status.ReadingLedgerCountsSource
import app.snapsync.feature.status.ShareableCountSource
import app.snapsync.feature.status.SyncStatusSource
import app.snapsync.feature.trust.DeviceAttestation
import app.snapsync.feature.upload.ComposedProducers
import app.snapsync.feature.upload.UploadArm
import app.snapsync.feature.upload.UploadProducer
import app.snapsync.flow.Background
import app.snapsync.flow.DownloadBackstop
import app.snapsync.flow.Foreground
import app.snapsync.flow.Provision
import app.snapsync.flow.SilentPush
import app.snapsync.model.AssetFacts
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.EventConfig
import app.snapsync.model.instantToCutoff
import app.snapsync.model.JoinLoad
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RawAsset
import app.snapsync.model.Resource
import app.snapsync.model.SelectionScope
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.model.UserCommands
import app.snapsync.ports.AlbumManager
import app.snapsync.ports.AlbumMapStore
import app.snapsync.ports.AttestClient
import app.snapsync.ports.AttestKey
import app.snapsync.ports.AttestStore
import app.snapsync.ports.ConfigSource
import app.snapsync.model.DiagnosticEnvironment
import app.snapsync.ports.DeviceLogSource
import app.snapsync.ports.DiagnosticsReporter
import app.snapsync.ports.ConfigStore
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.EventCreation
import app.snapsync.ports.EventRename
import app.snapsync.ports.EventDetails
import app.snapsync.ports.EventDirectory
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.Enrollment
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.LogScope
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.PhotoSelectionChangeSource
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The ports (and shell-supplied inputs) the app-graph composition consumes (spec
 * `module-architecture`, "One shared composition"). The shell constructs its platform adapters and
 * supplies them here; [snapSyncApp] composes the feature graph.
 *
 * Some inputs are deliberately **lambdas built by the shell**: the coordination hooks ([provision],
 * [onEventMinted], [notifyLeave]) bridge into the shell's entry surfaces; [uploadProducer] is the
 * resolved tier's *mechanism* thunk — since step 8 C3 the shell selects it via the pure sealed
 * `resolveComposition` switch (spec `module-architecture`, "One shared composition"), so only the
 * selected tier's mechanism is ever constructed; [albumExcludedAssetIds]
 * carries the app process's admit-on-doubt wrapper, shared verbatim with the own-device status
 * total so the two consumers of the policy can never diverge.
 */
class AppPorts(
    val configSource: ConfigSource,
    val configStore: ConfigStore,
    val photoAccess: PhotoAccessStatusSource,
    /** The photo-access request/Settings surface — the port behind the bundle's `requestAccess` /
     *  `openSettings` user-tap commands (migration step 9: presentation fires them through the
     *  bundle and never names the port). */
    val photoAccessRequester: PhotoAccessRequester,
    val candidateSource: CandidateSource,
    /** The **facts-only** cutoff-bounded gallery read for the join-time shareable-count preview
     *  (capability `join-share-count`): a `RawAssetSource.factsSince` — cheap `PHAsset` facts, NO per-asset
     *  resource round-trip. Default `{ emptyList() }` keeps the count at zero wherever it is not wired. */
    /** Read-only in this graph: the app-side ledger handle (aggregates read; the arm never writes records). */
    val ledger: LedgerStore,
    val downloadStore: DownloadStore,
    val importer: PhotoLibraryImporter,
    /** The durable download-staging directory — read lazily (an App-Group container lookup on iOS). */
    val downloadStagingRoot: () -> String,
    val newDownloadTransport: (DownloadTransportHost) -> DownloadTransport,
    val union: EventUnionSource,
    val directory: EventDirectory,
    /** The enrollment PUT — production passes `:adapter:generic:app`'s `HttpEnrollment`. */
    val enrollment: Enrollment,
    /**
     * The **same** manifest record the upload tier's producer keeps (`UploadPorts.manifestStore`).
     * Enrolling overwrites the server's manifest with an empty one, so it must invalidate that record or
     * the producer skips the rewrite — see [app.snapsync.feature.membership.ManifestDeviceEnroller].
     * Required rather than defaulted: a shell that quietly omitted it would reproduce the bug exactly.
     */
    val manifestStore: DeviceManifestStore,
    val eventCreation: EventCreation,
    /**
     * The event-rename seam (capability `event-rename`). **Required, not defaulted**: an inert default
     * would leave the status screen's rename affordance visible and silently doing nothing — the one
     * outcome [UserCommands.sendDiagnostics]'s contract forbids, and the reason that command is nullable
     * rather than inert. A root that cannot rename must fail to compile, not ship a dead pen.
     */
    val eventRename: EventRename,
    val attestKey: AttestKey,
    val attestClient: AttestClient,
    val attestStore: AttestStore,
    /** The device-identity resolve; throws while protected data is unavailable. Kept a thunk so no
     *  composition-time resolve can abort a locked background launch. */
    val deviceId: () -> String,
    val now: () -> Long,
    /** The **app-driven** upload mechanism — always composed (it serves iOS 18–26.0 fully and every
     *  OS under a partial grant); a thunk so it resolves lazily. Which composed producer RUNS is the
     *  tested arm's decision, by current permission (`upload-lifecycle`). */
    val uploadProducer: () -> UploadProducer,
    /** The **OS-driven** upload mechanism where this process composed one (iOS ≥26.1; never under the
     *  tier-force flag) — `null` elsewhere, keeping that mechanism entirely unconstructed there. */
    val osUploadProducer: () -> UploadProducer? = { null },
    val albumManager: AlbumManager,
    val albumMapStore: AlbumMapStore,
    val albumExcludedAssetIds: suspend (cutoff: CaptureCutoff) -> Set<String>,
    /** Invalidate the shared discovery cursor (capability `reconfigure-membership`): `ReconfigureEvent`
     *  calls it on a cutoff-lowering so the next cycle re-enumerates and back-shares the newly-in-scope
     *  older photos on both tiers. Default no-op keeps other compositions unaffected. */
    val clearDiscoveryCursor: () -> Unit = {},
    val notifyLeave: suspend (eventId: String) -> Unit,
    val provision: suspend (EventConfig) -> Unit,
    val onEventMinted: suspend (eventId: String) -> Unit,
    /** Crash/error reporting (capability `crash-reporting`). Required — a tier that forgot it would
     *  fail invisibly, exactly like the reconcile this bundle also refuses to default. */
    val diagnosticsReporter: DiagnosticsReporter,
    /** The device logs a diagnostic dump reads back (capability `diagnostic-logging`). The default
     *  reads nothing: off-device compositions (world, harnesses) have no device logs, and a dump
     *  assembled there is honestly empty rather than fabricated. */
    val deviceLogSource: DeviceLogSource = DeviceLogSource.None,
    /** Build/OS/tier facts the shell transcribes for a dump's state section — a value, like `OsFacts`,
     *  because every field is a constant of the running build rather than a seam to be stubbed. */
    val diagnosticEnvironment: DiagnosticEnvironment = DiagnosticEnvironment.UNKNOWN,
    // ── Shell/platform effect lambdas the `flow/` triggers coordinate over (migration step 8) ──
    // Each is a port/platform touch a flow may not make directly (law "flow/ never references ports/"):
    // the shell supplies it here and `compose/` passes it to the flow. All default to inert for the
    // world harness / tests, which drive the features directly and never mount a flow.
    //
    // Every Unit-returning one is `suspend` — law "A trigger flow never outlives its own run". Not
    // because they all suspend (a `BGTaskScheduler` submit does not), but because a flow cannot see
    // which of them the shell backed with a detached launch, and a non-suspend `() -> Unit` can only
    // ever be fire-and-forget. Typing them `suspend` is what makes the flow's await mean something.
    /** Renew the attestation token if stale — a wake point (`device-attestation`). */
    val refreshAttestation: suspend () -> Unit = {},
    /** Re-read the persisted membership into the config StateFlow (migration step 12: every trigger
     *  flow re-reads before acting — cross-process writes and a pre-first-unlock seed never notify
     *  this process's StateFlow). The default is inert: world/tests hold their config in-process. */
    val reloadConfig: suspend () -> Unit = {},
    /** Pump the app-driven upload tier on foreground; a no-op on iOS ≥26.1 (`ios-url-session-upload`). */
    val pumpForeground: suspend () -> Unit = {},
    /** Queue the download import-tail backstop `BGProcessingTask` (`photo-download` 5.4). */
    val scheduleBackstop: suspend () -> Unit = {},
    /** Present the platform share surface for the invite URL (`UIActivityViewController` on iOS) —
     *  the platform half of the [UserCommands.share] command; the default keeps it inert off-device. */
    val share: (String) -> Unit = {},
    /** Present the platform's limited-library picker — the platform half of the
     *  [UserCommands.choosePhotos] command (capability `limited-photo-access`); inert off-device. */
    val presentPhotoPicker: () -> Unit = {},
    /** The upload arm's silent-push receiver on the app-driven tier, or `null` on iOS ≥26.1. A thunk so
     *  the tier controller (which depends on this graph) resolves lazily, never at composition time. */
    val uploadSilentPush: () -> (suspend (eventId: String) -> Unit)? = { null },
    /** Selection snapshots under a partial grant (capability `limited-photo-access`); the inert default
     *  serves every composition that never sees one (world by default, desktop harnesses). */
    val selectionChanges: PhotoSelectionChangeSource = PhotoSelectionChangeSource.None,
    /** Drive one app-driven upload cycle for a selection change (the pump's `onSelectionChanged`),
     *  wired by the shell to the tier controller; inert where no app-driven tier exists. */
    val pumpSelectionChanged: suspend () -> Unit = {},
    /** Re-register the device's APNs push token on join (capability `push-registration`): the shell
     *  builds it from its `PushRegistration` + `PushTokenSource`. Inert by default (world/tests hold no
     *  push stack). Closes the warm-rejoin window the nightly sweep's config collection opens. */
    val registerPush: suspend () -> Unit = {},
    val log: Logger,
    /** The ambient-context seam the tier-neutral features drive so their device-log lines carry the
     *  triggering entry point's `[<name>]` prefix (capability `diagnostic-logging`). The app shell
     *  injects `IosLogScope`; world / tests default to `LogScope.NoOp`. */
    val logScope: LogScope = LogScope.NoOp,
)

/**
 * The composed app graph. Every property is `by lazy`, mirroring the composition root's previous
 * lazy web **byte-for-byte in construction timing**: nothing here resolves the device identity or
 * touches a platform store until the same first-use moment the root's own lazies did — the property
 * a locked background launch depends on.
 */
class AppCore internal constructor(
    private val scope: CoroutineScope,
    private val ports: AppPorts,
) {

    init {
        // First act, not lazy: the reporter must be live before any other wiring can fail. Reads
        // only this process's bundle config — safe on a locked background launch.
        ports.diagnosticsReporter.start()
    }

    /**
     * Device attestation (capability `device-attestation`) — the bearer token EVERY backend call
     * carries. Composed here; only the app process can attest (`DCAppAttestService.isSupported` is
     * false in an app extension — measured), and the extension reads the token this writes.
     */
    val attestation: DeviceAttestation by lazy {
        DeviceAttestation(
            key = ports.attestKey,
            client = ports.attestClient,
            store = ports.attestStore,
            deviceId = ports.deviceId,
            now = ports.now,
        )
    }

    // Own-device completeness AND in-flight, both from one consistent ledger `aggregates()` read
    // (capability `sync-status`). Read-only; on any failure the last good counts are retained.
    val ledgerCounts: ReadingLedgerCountsSource by lazy {
        ReadingLedgerCountsSource {
            ports.ledger.aggregates().let { LedgerCounts(completed = it.completed, pending = it.pending) }
        }
    }

    // The own-device upload TOTAL N (capability `sync-status`): gallery enumeration minus downloaded
    // foreign photos, scoped by the membership's cutoff and the origin exclusions
    // (capability `photo-selection-policy`) — the SAME album lookup the upload cycle gets, because the
    // two enumerate independently and a rule applied to one and not the other would peg the joined
    // screen below 100% forever.
    /**
     * The one read seam the app's consumers hold — the grant decides the backing, not the consumer
     * (capability `limited-photo-access`). Built here because choosing between ports by a third port's
     * state is composition.
     */
    private val candidates: CandidateSource by lazy {
        PermissionAwareCandidateSource(
            permission = ports.photoAccess.permission,
            walk = ports.candidateSource,
            selection = latestSelectionSnapshot,
        )
    }

    val gallery: OwnDeviceGalleryStatusSource by lazy {
        OwnDeviceGalleryStatusSource(
            candidates,
            suppressedLocalIds = { ports.downloadStore.suppressedLocalIds() },
            albumExcludedAssetIds = ports.albumExcludedAssetIds,
        )
    }

    // The join-time shareable-count preview (capability `join-share-count`): the SAME policy the cycle and
    // `gallery` (N) apply, over the same permission-aware source — so the preview and the total cannot
    // disagree about where candidates come from. No usable grant → null → the surface omits the row.
    private val shareableCountSource: ShareableCountSource by lazy {
        ShareableCountSource(
            source = candidates,
            suppressedLocalIds = { ports.downloadStore.suppressedLocalIds() },
            albumExcludedAssetIds = ports.albumExcludedAssetIds,
        )
    }

    /**
     * The join surface's live "how many photos from your gallery will be shared" query (capability
     * `join-share-count`): for a candidate [cutoff] with sharing on, the count of own photos the policy
     * admits, or `null` when the grant permits no count. Purely local — no backend LIST.
     */
    suspend fun loadShareableCount(cutoff: CaptureCutoff, until: CaptureCeiling?): Int? =
        shareableCountSource.count(
            includesUpload = true,
            cutoff = cutoff,
            ceiling = until,
            permission = ports.photoAccess.permission.value,
        )

    /** The photo-access grant, exposed for the join surface's count-recompute trigger (a late resolve). */
    val photoPermission: StateFlow<PermissionStatus> get() = ports.photoAccess.permission

    /** The real ledger-backed status source (ledger truth × permission × gallery total). */
    val syncStatusSource: SyncStatusSource by lazy {
        LedgerBackedSyncStatusSource(ledgerCounts, ports.photoAccess, gallery, scope)
    }

    // Download progress for the joined-layer "downloaded X of Y" line (capability `photo-download`).
    val downloadStatusSource: StoreDownloadStatusSource by lazy {
        StoreDownloadStatusSource(ports.downloadStore)
    }
    val downloadStatus: DownloadStatusSource get() = downloadStatusSource

    // Background byte transfers → durable staging. The queue, bounded window, and cancellation
    // lifecycle live in the tested feature; the transport is the shell's adapter thunk.
    val downloadJobs: QueuedPhotoDownloadJobs by lazy {
        QueuedPhotoDownloadJobs(scope, ports.downloadStagingRoot(), ports.newDownloadTransport)
    }

    // The download orchestrator: union → foreign selection → download → import → suppression.
    val downloadController: DownloadController by lazy {
        val controller = DownloadController(
            union = ports.union,
            store = ports.downloadStore,
            jobs = downloadJobs,
            importer = ports.importer,
            myDeviceId = ports.deviceId(),
            // Three-valued, no fallback (capability `photo-download`): no membership → `null` → no arm.
            downloadEnabled = { ports.configSource.config.value?.direction?.includesDownload },
            logScope = ports.logScope,
        )
        // Deliver each staged resource back to the controller off the transport delegate thread —
        // an adapter outbound callback satisfied by a compose-built lambda (law: "Commands cross one
        // door" — a compose-built single-command lambda is the sanctioned adapter-callback form).
        // No launch here: `QueuedPhotoDownloadJobs` owns it, so it can join the imports before the
        // background session's OS handler is released (capability `photo-download`). A fire-and-forget
        // launch in a compose adapter callback is exactly the shape that left work unreachable.
        downloadJobs.onStaged = { ref, key, path -> controller.onResourceStaged(ref, key, path) }
        controller
    }

    // The silent-push receiver for the download arm (capability `photo-download`); its active-event
    // guard is the feature's rule. The app shell fans this out with the upload arm's receiver until
    // the fan-out re-homes (step 8).
    val downloadPushReceiver: DownloadPushReceiver by lazy {
        DownloadPushReceiver(
            activeEventId = { ports.configSource.config.value?.eventId },
            controller = downloadController,
        )
    }

    // Event album (capability `event-album`): the coordinator over the shared leave-surviving map.
    // The APP is the SOLE creator (on the permission grant); both processes only add.
    val albumCoordinator: AlbumCoordinator by lazy {
        AlbumCoordinator(ports.albumManager, ports.albumMapStore)
    }

    // The tier-neutral upload lifecycle (capability `upload-lifecycle`): which producer verb fires on
    // which membership transition. The root defaults nothing — an absent membership is `null`, and
    // the decision lives in the tested arm.
    val uploadArm: UploadArm by lazy {
        UploadArm(
            // Every composed producer; which one RUNS is the arm's permission decision — the OS-driven
            // mechanism under GRANTED (where composed), the app-driven one under LIMITED (the OS never
            // invokes the extension there — measured; `ios-photokit-upload`). A cycle started under
            // LIMITED is read-free by construction: its discovery consumes the selection snapshot
            // (`SelectionScopedTransfer`), never a library walk.
            producers = ComposedProducers(
                osDriven = ports.osUploadProducer(),
                appDriven = ports.uploadProducer(),
            ),
            permission = { ports.photoAccess.permission.value },
            membershipIncludesUpload = { ports.configSource.config.value?.direction?.includesUpload },
            log = ports.log,
            logScope = ports.logScope,
        )
    }

    // The leave use-case: stop the producer, clear the config (which flips the screen off the joined
    // layer), then notify the backend fire-and-forget. It destroys no dedup state.
    val leaveEvent: LeaveEvent by lazy {
        LeaveEvent(
            config = ports.configStore,
            configSource = ports.configSource,
            stopUploads = { uploadArm.onLeave() },
            scope = scope,
            notifyLeave = ports.notifyLeave,
        )
    }

    // The in-place reconfigure use-case (capability `reconfigure-membership`): rewrite the joined
    // membership's participation fields (direction/cutoff/album) whole, then re-drive the provision-side
    // effects. Upload ARMS on enable but drains on disable (no stop); download reconciles on enable and
    // cancels in-flight on disable — the deliberate arm asymmetry lives in the tested use-case.
    val reconfigureEvent: ReconfigureEvent by lazy {
        ReconfigureEvent(
            configSource = ports.configSource,
            store = ports.configStore,
            refreshStatus = { refreshStatusSources() },
            armUpload = { uploadArm.onProvision() },
            ensureAlbum = { cfg ->
                albumCoordinator.ensureAlbum(
                    cfg.eventId,
                    cfg.name,
                    cfg.saveToAlbum,
                    granted = ports.photoAccess.permission.value.grantsPhotoAccess,
                )
            },
            // On its own escaping launch (like Provision's reconcile), so a slow union read never blocks
            // the command's return.
            startDownloads = { eventId -> scope.launch { downloadController.reconcile(eventId) } },
            cancelDownloads = { downloadController.onLeaveOrSwitch() },
            // A cutoff-lowering reconfigure re-shares the newly-in-scope older photos on both tiers by
            // invalidating the forward-only discovery cursor (capability `reconfigure-membership`).
            clearDiscoveryCursor = ports.clearDiscoveryCursor,
        )
    }

    // The join use-case (capability `join-event`): fetch details, enroll by writing the register-only
    // EMPTY device manifest, then provision through the same path as create/scan.
    val joinEvent: JoinEvent by lazy {
        JoinEvent(
            configSource = ports.configSource,
            deviceId = ports.deviceId,
            details = ports.directory,
            enroller = ManifestDeviceEnroller(ports.enrollment, ports.manifestStore),
            provision = ports.provision,
        )
    }

    // The membership-refresh rule (capability `join-event`): what a fetched details result MEANS for the
    // persisted membership — seated in `feature/membership` because that config is the feature's durable
    // state. The *fetch* it pairs with is [fetchEventDetails], coordinated by the Foreground flow — the
    // sole trigger that refreshes. It reads the clock because the absence verdict needs a second,
    // OFFLINE witness.
    val membershipRefresh: MembershipRefresh by lazy {
        MembershipRefresh(
            configSource = ports.configSource,
            store = ports.configStore,
            now = { instantToCutoff(Instant.fromEpochMilliseconds(ports.now())) },
            leaveEvent = leaveEvent,
        )
    }

    // The `GET /events/:id` fetch — the `EventDirectory` port effect the flows coordinate over, built
    // here because a flow may not touch a port directly (law "flow/ never references ports/").
    //
    // It carries the SEALED outcome, via the same `toJoinLoad` mapping the join gate uses. This used to
    // flatten to `Found?` with an `as?` cast, deliberately, so that no fetch result could ever be
    // destructive — "offline", "parse failure", and "the event is gone" arrived as one indistinguishable
    // `null`. That blindness is now replaced by something strictly stronger rather than merely removed:
    // the rule requires a definitive `NotFound` AND the membership's own persisted deadline before it
    // will tear anything down (capability `leave-event`).
    private val fetchEventDetails: suspend (eventId: String) -> JoinLoad = { eventId ->
        ports.directory.fetch(eventId).toJoinLoad()
    }

    /** The create-event status the use-case drives and the container reads (same instance). */
    val creationStatus: MutableCreationStatusSource = MutableCreationStatusSource()

    /** The rename status the use-case drives and the container reads (same instance, capability
     *  `event-rename`) — the create twin, but carrying a success value the screen must clear. */
    val renameStatus: MutableRenameStatusSource = MutableRenameStatusSource()

    // The rename use-case (capability `event-rename`): rewrite the shared event's name on the backend,
    // then fold the ECHOED name into this membership's config. The fifth writer of that config, seated
    // in `feature/membership` beside the reconfigure for exactly that reason.
    val renameEvent: RenameEvent by lazy {
        RenameEvent(
            configSource = ports.configSource,
            store = ports.configStore,
            client = ports.eventRename,
            status = renameStatus,
            scope = scope,
        )
    }

    // The create-event use-case: mint via the backend, then route the minted event into the SAME
    // join gate a scanned QR takes (capability `photo-selection-policy`).
    val eventCreator: EventCreator by lazy {
        CreateEvent(
            client = ports.eventCreation,
            status = creationStatus,
            scope = scope,
            onMinted = ports.onEventMinted,
        )
    }

    /**
     * The headless create-event use-case (capability `ios-app-shell`, `SNAPSYNC_CREATE_EVENT`): mint an
     * event and either report its id (mint-only) or forward a synthesized `autoJoin` link the shell wires
     * to `onOpenUrl`. Distinct from [eventCreator] (which routes into the interactive, tap-gated join
     * gate). `now` supplies the canonical `…Z` default for a payload with no `startsAt`, derived from the
     * same `ports.now` epoch-millis clock every other seam uses.
     */
    val headlessCreate: HeadlessCreate by lazy {
        HeadlessCreate(
            client = ports.eventCreation,
            log = ports.log,
            now = { instantToCutoff(Instant.fromEpochMilliseconds(ports.now())) },
        )
    }

    /**
     * The headless membership-trigger coordinator (capability `ios-app-shell`): applies
     * `reset → leave → create → event-link` in order for the launch-env triggers. Owns the ordering the
     * shell may not (`architecture-guards`); its effects are built here — [leave] the leave command, the
     * best-effort attestation refresh, the durable-state reset — while the shell supplies its
     * `onOpenUrl` join entry to `run`.
     */
    val launchEnvMembership: LaunchEnvMembership by lazy {
        LaunchEnvMembership(
            headlessCreate = headlessCreate,
            log = ports.log,
            leave = { userCommands.leave() },
            ensureAttested = { runCatching { attestation.ensureFresh() } },
            resetState = { resetDeviceState.reset() },
        )
    }

    /**
     * Voids this device's durable sync state (the `SNAPSYNC_RESET_STATE` trigger, capability
     * `ios-app-shell`) so a build pointed at a different backend starts from nothing. The cursor
     * invalidation reuses the SAME [AppPorts.clearDiscoveryCursor] effect `ReconfigureEvent` uses, so
     * there is one surface for "make the next cycle re-enumerate" rather than two that could diverge.
     */
    private val resetDeviceState: ResetDeviceState by lazy {
        ResetDeviceState(
            config = ports.configStore,
            ledger = ports.ledger,
            downloads = ports.downloadStore,
            clearDiscoveryCursor = ports.clearDiscoveryCursor,
        )
    }

    // ---- Selection-driven reads under a partial grant (capability `limited-photo-access`) -----------
    // The latest selection snapshot (set only by the selection subscription below). The walk-vs-snapshot
    // decision is DERIVED per read from current permission + this cell, so it has exactly one owner and
    // no stored mode can go stale across a permission flip.
    private val latestSelectionSnapshot = MutableStateFlow<List<Resource>?>(null)

    /**
     * What upload discovery may read right now (consumed by the tier controllers' `uploadCore` ports):
     * unrestricted under a full grant; the latest snapshot under a partial one. `Scoped(empty)` between
     * the grant turning partial and the first snapshot is the honest gap — discovery finds nothing,
     * rather than walking.
     */
    fun selectionScope(): SelectionScope =
        if (ports.photoAccess.permission.value == PermissionStatus.LIMITED) {
            SelectionScope.Scoped(latestSelectionSnapshot.value ?: emptyList())
        } else {
            SelectionScope.Unrestricted
        }

    // Re-read the own-device gallery total (enumeration, downloads suppressed), the ledger counts
    // (completed + in-flight), and the foreign download line (capability `sync-status`). No membership
    // → nothing to count; a download-only membership counts 0 too — the source's decision from the
    // Contribution, not a branch here (the roots pass facts, never branches).
    suspend fun refreshStatusSources() {
        ports.configSource.config.value?.let { cfg ->
            // The own-device walk enumerates the library — GRANTED exactly, per the read discipline
            // (`limited-photo-access`): under LIMITED the total derives from the selection-driven
            // discovery instead of an autonomous enumeration.
            if (ports.photoAccess.permission.value == PermissionStatus.GRANTED) {
                gallery.refresh(SelectionPolicy.from(cfg))
            }
        }
        ledgerCounts.refresh()
        downloadStatusSource.refresh() // the "downloaded X of Y" line (capability `photo-download`)
    }

    // ── The OS-callback trigger flows (spec `module-architecture`, "Rules in features, order in
    // flows"; migration step 8). Each is built here — features referenced directly, port/platform
    // touches injected from [ports] — and the shell entry points delegate to them. ────────────────

    // The foreground-gated ledger-counts poll (capability `sync-status`; migration step 12): started
    // by the Foreground flow, stopped by the Background flow — the cadence is the feature's rule.
    val ledgerCountsPoller: LedgerCountsPoller by lazy {
        LedgerCountsPoller(scope, ledgerCounts)
    }

    val foregroundFlow: Foreground by lazy {
        Foreground(
            downloadController = downloadController,
            membershipRefresh = membershipRefresh,
            statusPoller = ledgerCountsPoller,
            reloadConfig = ports.reloadConfig,
            // Permission routes the foreground pump: under GRANTED the tier's own thunk (which walks —
            // and is `{}` on iOS ≥26.1, where the OS owns upload scheduling); under LIMITED the
            // app-driven selection drain, which is read-free by construction (its discovery consumes
            // the snapshot — `SelectionScopedTransfer`), so the read discipline holds
            // (`limited-photo-access`, "No autonomous library reads") while a reopened app still
            // catches up on pending uploads. Everything else this flow coordinates (reconcile, poller,
            // attestation) runs under any permission.
            pumpForeground = {
                when (ports.photoAccess.permission.value) {
                    PermissionStatus.GRANTED -> ports.pumpForeground()
                    PermissionStatus.LIMITED -> ports.pumpSelectionChanged()
                    PermissionStatus.NOT_DETERMINED, PermissionStatus.DENIED -> Unit
                }
            },
            refreshStatus = { refreshStatusSources() },
            activeEventId = { ports.configSource.config.value?.eventId },
            fetchEventDetails = fetchEventDetails,
            refreshAttestation = ports.refreshAttestation,
        )
    }

    val backgroundFlow: Background by lazy {
        Background(statusPoller = ledgerCountsPoller, scheduleBackstop = ports.scheduleBackstop)
    }

    val silentPushFlow: SilentPush by lazy {
        SilentPush(
            reloadConfig = ports.reloadConfig,
            refreshAttestation = ports.refreshAttestation,
            // Download arm first, then the upload arm on the app-driven tier (order preserved from the
            // former FanOutPushReceiver). The upload receiver is a thunk so the tier controller resolves
            // lazily; on iOS ≥26.1 it is null and only the download arm is woken.
            receivers = buildList {
                add(downloadPushReceiver::onSilentPush)
                // The upload receiver drives a cycle (a library walk) — GRANTED exactly, per the read
                // discipline (`limited-photo-access`): under LIMITED only the download arm is woken.
                ports.uploadSilentPush()?.let { receiver ->
                    add { eventId ->
                        if (ports.photoAccess.permission.value == PermissionStatus.GRANTED) receiver(eventId)
                    }
                }
            },
        )
    }

    val downloadBackstopFlow: DownloadBackstop by lazy {
        DownloadBackstop(
            downloadController = downloadController,
            reloadConfig = ports.reloadConfig,
            refreshAttestation = ports.refreshAttestation,
        )
    }

    val provisionFlow: Provision by lazy {
        Provision(
            uploadArm = uploadArm,
            downloadController = downloadController,
            albumCoordinator = albumCoordinator,
            activeEventId = { ports.configSource.config.value?.eventId },
            notifyLeave = ports.notifyLeave,
            saveConfig = { cfg -> ports.configStore.save(cfg) },
            refreshStatus = { refreshStatusSources() },
            // Usable access (`grantsPhotoAccess`): this gate feeds only ensureAlbum's granted
            // parameter, and album creation works under a LIMITED grant (measured — capability
            // `limited-photo-access`).
            isGranted = { ports.photoAccess.permission.value.grantsPhotoAccess },
            registerPush = ports.registerPush,
        )
    }

    // ── The user-tap command bundle (spec `module-architecture`, "Commands cross one door"): built and
    // decorated only here in `compose/`, injected into `StatusContainerHost` by constructor — so
    // presentation never references a feature command directly. Each command's body is the exact
    // coordination the shell's individual lambdas used to carry (migration step 8 C3). ────────────────

    /**
     * Wrap a user tap as a **platform entry point** (spec `diagnostic-logging`; spec
     * `module-architecture`, "Absence is never silent"). `compose/` is where this must live: it is
     * where the door law already says command instances are decorated, and it is the only place that
     * *can* — `:ui:presentation` may not reference `ports/`, so it cannot reach a `LogScope`.
     *
     * The `tap.` namespace is load-bearing, not cosmetic. Without it a device log cannot say whether
     * work was started by the platform or by the person holding the phone: on Bugsink `SNAPSYNC-3`,
     * proving that a leave was a manual tap rather than the switch path's backend notify took reading
     * two source files, because both produce the same downstream lines.
     */
    private val tapLog = Logger.withTag("userTap")

    val userCommands: UserCommands by lazy {
        UserCommands(
            // Leave: cancel in-flight downloads and drop non-terminal rows (imported photos stay;
            // suppression rows are permanent), then run the leave use-case (disable producer → notify
            // the backend it is leaving → clear config/producer). Imported foreign photos are never
            // touched.
            leave = {
                tapLog.invocation(ports.logScope, "tap.leave") {
                    downloadController.onLeaveOrSwitch()
                    leaveEvent.leave()
                }
            },
            // Create: mint via the backend; the use-case routes the minted event into the SAME join
            // gate a scanned QR takes (fire-and-forget; outcomes ride `creationStatus`).
            create = { name, startsAt, endsAt ->
                tapLog.invocation(ports.logScope, "tap.create") {
                    eventCreator.create(name, startsAt.at.iso, endsAt.at.iso)
                }
            },
            // The join gate's commit (capability `join-event`): enroll (register-only empty manifest)
            // then provision. `true` unless enrollment failed (the same-event no-op is a success).
            commitJoin = {
                eventId, name, startsAt, endsAt, deletesAt, minPhotoDate, maxPhotoDate, direction,
                saveToAlbum,
                ->
                tapLog.invocation(
                    ports.logScope,
                    "tap.commitJoin",
                    params = "eventId=$eventId",
                    result = { joined: Boolean -> "joined=$joined" },
                ) {
                    joinEvent.join(
                        eventId, name, startsAt, endsAt, deletesAt, minPhotoDate, maxPhotoDate, direction,
                        saveToAlbum,
                    ) != JoinOutcome.EnrollFailed
                }
            },
            // Share is pure platform (a system sheet over the top view controller). Decorated like the
            // rest: presenting the sheet is still a tap, and an unattributed line is the thing this
            // instrumentation exists to eliminate.
            share = { url ->
                tapLog.invocation(ports.logScope, "tap.share") { ports.share(url) }
            },
            // The permission user-taps (capability `permission-gate`), bound to the requester port here
            // so presentation never names it (migration step 9). `requestAccess` returns nothing and
            // cannot suspend — the grant arrives only via the permission read-model StateFlow.
            requestAccess = {
                tapLog.invocation(ports.logScope, "tap.requestAccess") { ports.photoAccessRequester.request() }
            },
            openSettings = {
                tapLog.invocation(ports.logScope, "tap.openSettings") { ports.photoAccessRequester.openSettings() }
            },
            // The picker presentation is platform surface; the selection outcome arrives only via
            // the selection-change seam (fire-and-forget, like every command here).
            choosePhotos = {
                tapLog.invocation(ports.logScope, "tap.choosePhotos") { ports.presentPhotoPicker() }
            },
            // In-place membership reconfigure (capability `reconfigure-membership`): edit direction/
            // cutoff/album without leaving. Distinct from `openSettings` (the iOS system settings page).
            reconfigure = { eventId, direction, minPhotoDate, maxPhotoDate, saveToAlbum ->
                tapLog.invocation(ports.logScope, "tap.reconfigure", params = "eventId=$eventId") {
                    reconfigureEvent.reconfigure(eventId, direction, minPhotoDate, maxPhotoDate, saveToAlbum)
                }
            },
            // Rename the joined event (capability `event-rename`): unlike `reconfigure`, which edits only
            // this device's settings, this rewrites the SHARED event — every member picks the new name up
            // on their next foreground refresh. Fire-and-forget; the outcome rides `renameStatus`.
            rename = { eventId, name ->
                tapLog.invocation(ports.logScope, "tap.rename", params = "eventId=$eventId") {
                    renameEvent.rename(eventId, name)
                }
            },
            // Clear the rename latch once the screen has consumed a terminal status. Instrumented like
            // the taps even though it is a screen-fired acknowledgement rather than a tap: it mutates
            // the rename lifecycle, and an unattributed state change is the thing this trail exists to
            // eliminate.
            resetRename = {
                tapLog.invocation(ports.logScope, "tap.resetRename") { renameEvent.reset() }
            },
            // The hidden diagnostic dump (capability `diagnostic-logging`), fired once the operator has
            // written what went wrong. NULL on a build with no reporting configuration, so the screen
            // wires no gesture and no sheet can open — a build that can send nothing must not offer an
            // affordance suggesting it can. This is the ONE place that decision is made.
            sendDiagnostics = if (ports.diagnosticsReporter.isConfigured) {
                { note, screen ->
                    tapLog.invocation(ports.logScope, "tap.sendDiagnostics", params = "screen=$screen") {
                        ports.diagnosticsReporter.send(collectDiagnosticDump.collect(note, screen))
                    }
                }
            } else {
                null
            },
        )
    }

    /**
     * The diagnostic dump assembly (capability `diagnostic-logging`) — reads only, and only what this
     * graph already holds. Composed lazily like everything else here, so an unconfigured build (which
     * never fires the command) never builds it.
     */
    private val collectDiagnosticDump: CollectDiagnosticDump by lazy {
        CollectDiagnosticDump(
            environment = ports.diagnosticEnvironment,
            logs = ports.deviceLogSource,
            ledger = ports.ledger,
            downloads = ports.downloadStore,
            config = ports.configSource,
            permission = ports.photoAccess,
        )
    }

    /**
     * Install the two **port-state-transition subscriptions** on the permission StateFlow (spec
     * `module-architecture`, "Commands cross one door": installed in `compose/`; the transition
     * semantics — start-on-grant, sole-creator album ensure — are feature rules). Matching the shell's
     * former `startUploadsOnGrant` + `ensureAlbumOnGrant`, both fire only on a *transition* to GRANTED
     * (a StateFlow conflates an unchanged value), so neither can rescue a membership provisioned while
     * access was already granted — the provision flow owns that case.
     *
     * Deliberately an **explicit step, not `init`** (step 8 C3, restoring the pre-C2 timing): the app
     * shell invokes it from its host-assembly path — the only place the collectors ever installed — so
     * a cold backstop/URLSession wake that merely touches [AppCore] starts **no** producer via the
     * permission StateFlow's replay, exactly as before. Call it once; each call installs a fresh pair
     * of collectors.
     */
    fun installPermissionSubscriptions() {
        scope.launch {
            // Every permission emission reaches the arm; the ARM decides (usable-access + the
            // membership posture + the permission-selected producer live in the tested orchestrator,
            // `upload-lifecycle`). A GRANTED ↔ LIMITED flip is a stop-then-start mechanism switch.
            ports.photoAccess.permission.collect { uploadArm.onPermissionChanged() }
        }
        scope.launch {
            // One selection-change emission → ONE read serving both consumers (capability
            // `limited-photo-access`, "One discovery serves both the status total and the enqueue"):
            // the cell feeds the cycle's discovery AND backs the permission-aware candidate source, so
            // `refresh` recounts N over the very same snapshot — no second library read on this path, and
            // no snapshot-specific entry point for the total to drift through.
            ports.selectionChanges.snapshots.collect { snapshot ->
                latestSelectionSnapshot.value = snapshot
                ports.configSource.config.value?.let { cfg -> gallery.refresh(SelectionPolicy.from(cfg)) }
                ports.pumpSelectionChanged()
            }
        }
        scope.launch {
            ports.photoAccess.permission.collect { status ->
                // The app is the sole album creator, and sync needs the same grant, so the album exists
                // before the first synced photo — both processes then only ADD (capability `event-album`).
                // Unconditional call: the membership's opt-in/name gate is the coordinator's own guard.
                // Usable access (`grantsPhotoAccess`): album creation works under a LIMITED grant
                // (measured — capability `limited-photo-access`), so a limited member's opted-in album
                // exists before their first import lands.
                if (status.grantsPhotoAccess) {
                    ports.configSource.config.value?.let { cfg ->
                        albumCoordinator.ensureAlbum(cfg.eventId, cfg.name, cfg.saveToAlbum)
                    }
                }
            }
        }
    }
}

/**
 * The ONE app-graph composition (spec `module-architecture`, "One shared composition"): the app
 * shell calls this — and, at migration step 10, the world harness does too — so a wiring difference
 * between binaries is impossible rather than undetected. Manual DI (decision D6 of
 * `establish-target-architecture`): plain constructors, no framework.
 */
fun snapSyncApp(scope: CoroutineScope, ports: AppPorts): AppCore = AppCore(scope, ports)
