package app.snapsync.compose

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.creation.CreateEvent
import app.snapsync.feature.creation.EventCreator
import app.snapsync.feature.creation.MutableCreationStatusSource
import app.snapsync.feature.diagnostics.CollectDiagnosticDump
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.DownloadPushReceiver
import app.snapsync.feature.download.DownloadStatusSource
import app.snapsync.feature.download.QueuedPhotoDownloadJobs
import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.feature.membership.MembershipRefresh
import app.snapsync.feature.membership.toJoinLoad
import app.snapsync.feature.push.PushRegistration
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
import app.snapsync.feature.upload.RelinquishThenRun
import app.snapsync.feature.upload.IdleUploadMechanism
import app.snapsync.feature.upload.UploadMechanismRuntime
import app.snapsync.model.UploadMechanism
import app.snapsync.model.resolveUploadMechanism
import app.snapsync.feature.upload.UploadArm
import app.snapsync.feature.upload.UploadProducer
import app.snapsync.flow.Background
import app.snapsync.flow.DownloadBackstop
import app.snapsync.flow.Foreground
import app.snapsync.flow.Provision
import app.snapsync.flow.SilentPush
import app.snapsync.model.AssetFacts
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionPolicyFor
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
import app.snapsync.ports.PushTokenSource
import app.snapsync.ports.Clock
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
import app.snapsync.ports.LeaveNotifier
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.LogScope
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.ImportedAssetPresence
import app.snapsync.ports.SharePresenter
import app.snapsync.ports.StagedBytes
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.PhotoSelectionChangeSource
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger
import kotlin.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ports (and shell-supplied inputs) the app-graph composition consumes (spec
 * `module-architecture`, "One shared composition"). The shell constructs its platform adapters and
 * supplies them here; [snapSyncApp] composes the feature graph.
 *
 * Some inputs are deliberately **lambdas built by the shell**: the coordination hooks ([provision],
 * [onEventMinted]) bridge into the shell's entry surfaces; [appDrivenUpload] and [osDrivenUpload] are
 * the mechanisms this OS can carry — which one RUNS is resolution's answer and not this bag's
 * (`upload-lifecycle`, "The upload mechanism is resolved, never selected"); [albumExcludedAssetIds]
 * carries the app process's admit-on-doubt wrapper, shared verbatim with the own-device status
 * total so the two consumers of the policy can never diverge.
 */
class AppPorts(
    val configSource: ConfigSource,
    val configStore: ConfigStore,
    val photoAccess: PhotoAccessStatusSource,
    /** The photo-access request/Settings/picker surface — the port behind the bundle's `requestAccess` /
     *  `openSettings` / `choosePhotos` user-tap commands (migration step 9: presentation fires them
     *  through the bundle and never names the port). `choosePhotos` was a separate
     *  `presentPhotoPicker: () -> Unit` field until this port absorbed it: it is the same need — hand the
     *  user back to the system to widen what this app may see — answered through the same read-models. */
    val photoAccessRequester: PhotoAccessRequester,
    /** The platform share surface for the invite URL — the platform half of the [UserCommands.share]
     *  command, and a port rather than the `(String) -> Unit` the shell used to fill with a UIKit
     *  presenter. [SharePresenter.None] keeps it inert off-device, where there is no surface to reach. */
    val sharePresenter: SharePresenter = SharePresenter.None,
    /**
     * The **main lane** (spec `module-architecture`, law "Dispatcher lanes are fixed by the
     * composition"): the dispatcher platform-UI commands run on — `share`, `requestAccess`,
     * `openSettings`, `choosePhotos`, which present system UI and must not leave the main thread.
     *
     * A port rather than a constant because a dispatcher is a platform fact: `:domain` may not name the
     * platform's main-thread dispatcher, which does not exist on every target this code compiles for and
     * is what the main-lane containment gate confines to platform-UI adapters. **Required, not
     * defaulted** — a default would silently put system UI on whatever lane the caller happened to
     * be on, which is the class of defect this law exists to end.
     */
    val uiLane: CoroutineContext,
    /** The permission-aware gallery read seam. ONE instance serves both the status total and the
     *  join-time shareable-count preview (capability `join-share-count`), so the two cannot disagree. */
    val candidateSource: CandidateSource,
    /** Read-only in this graph: the app-side ledger handle (aggregates read; the arm never writes records). */
    val ledger: LedgerStore,
    val downloadStore: DownloadStore,
    val importer: PhotoLibraryImporter,
    /**
     * Whether an asset this device created still exists — the **full-access** source only (capability
     * `photo-download`). Composition wraps it in [PermissionAwareAssetPresence] below, which is what
     * decides whether a miss may be reported as absence at all; a partial or revoked grant answers from
     * the held selection instead and never reaches this. Defaults to unanswerable so a composition that
     * cannot look never claims an asset is gone — the one wrong answer that re-creates the defect.
     */
    val assetPresence: ImportedAssetPresence = ImportedAssetPresence.Unanswerable,
    /**
     * Where downloaded bytes are staged, and who releases them once their row settles (capability
     * `download-store`).
     *
     * **Required, no longer defaulted.** It used to default to [StagedBytes.None] on the reasoning that
     * failing to free disk is the one harmless failure among these ports — true of releasing, and false
     * of the staging root this port now also owns (previously the separate `downloadStagingRoot: () ->
     * String` lambda, which had no default for exactly that reason). A composition that cannot say where
     * bytes land must not be able to download at all.
     */
    val stagedBytes: StagedBytes,
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
    /** Wall-clock now, through the port that has always existed for it (`ports/Time.kt`). This was a
     *  `() -> Long` lambda the shell filled with an inline `NSDate()` call — a platform read supplied
     *  to the core past a seam built for exactly this (spec `module-architecture`). Two clocks were
     *  therefore live in one composition: this one for the domain, `SystemClock` for the UI
     *  formatter, and a test could pin one and leave the other running. */
    val clock: Clock,
    /** The **app-driven** upload mechanism — always composed (it serves iOS 18–26.0 fully and every
     *  OS under a partial grant); a thunk so it resolves lazily. Which mechanism RUNS is resolution's
     *  answer, not this port's (`upload-lifecycle`, "The upload mechanism is resolved, never selected"). */
    val appDrivenUpload: () -> UploadMechanismRuntime,
    /** The **OS-driven** upload mechanism where this OS carries one (iOS ≥26.1) — `null` elsewhere,
     *  keeping that mechanism entirely unconstructed where its registration selector does not exist. */
    val osDrivenUpload: () -> UploadMechanismRuntime? = { null },
    /** Whether this OS carries the OS-driven mechanism at all — an input to resolution, kept a plain
     *  fact rather than derived from [osDrivenUpload] so resolving never has the side effect of
     *  constructing a mechanism it is only asking about. */
    val osSupportsOsDrivenUpload: Boolean = false,
    /** Deregister a surviving OS-driven registration — **deregistration only**, no ledger clear and no
     *  cursor reset (`upload-lifecycle`, [RelinquishThenRun]). Inert where no such registration exists. */
    val relinquishOsRegistration: suspend () -> Unit = {},
    /** A development pin on the resolved mechanism, read fresh at every resolution. **Always `null` in a
     *  production build**: its source exists only in a build made with the rig, so the mechanism a
     *  shipped process runs is still a function of the device it runs on. It restores the deleted
     *  `SNAPSYNC_FORCE_URLSESSION_UPLOAD` (decision record `2026-08-24-retire-launch-env-triggers`, D14). */
    val uploadMechanismOverride: () -> UploadMechanism? = { null },
    val albumManager: AlbumManager,
    val albumMapStore: AlbumMapStore,
    val albumExcludedAssetIds: suspend (cutoff: CaptureCutoff) -> Set<String>,
    /** Invalidate the shared discovery cursor (capability `reconfigure-membership`): `ReconfigureEvent`
     *  calls it on a cutoff-lowering so the next cycle re-enumerates and back-shares the newly-in-scope
     *  older photos on both tiers. Default no-op keeps other compositions unaffected. */
    val clearDiscoveryCursor: () -> Unit = {},
    /** Tells the shared event this device is leaving (capability `leave-event`). This was
     *  `notifyLeave: suspend (eventId) -> Unit`, a lambda the shell built by closing over the adapter
     *  AND this device's id — a backend call reaching out of the process behind a type indistinguishable
     *  from in-core coordination. The id now lives where it is a constant: in the adapter (see
     *  [LeaveNotifier]). The `flow/` and `feature/` consumers still take a lambda, which `compose/`
     *  builds from this port — they may not name a port at all (law "flow/ never references ports/"). */
    val leaveNotifier: LeaveNotifier,
    val provision: suspend (EventConfig) -> Unit,
    val onEventMinted: suspend (eventId: String) -> Unit,
    /** Crash/error reporting (capability `crash-reporting`). Required — a tier that forgot it would
     *  fail invisibly, exactly like the reconcile this bundle also refuses to default. */
    val diagnosticsReporter: DiagnosticsReporter,
    /** The device logs a diagnostic dump reads back (capability `diagnostic-logging`). The default
     *  reads nothing: off-device compositions (world, harnesses) have no device logs, and a dump
     *  assembled there is honestly empty rather than fabricated. */
    val deviceLogSource: DeviceLogSource = DeviceLogSource.None,
    /** Build/OS/tier facts the shell transcribes for a dump's state section — a transcribed value rather
     *  than a port, because every field is a constant of the running build rather than a seam to be
     *  stubbed. */
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

    /** Queue the download import-tail backstop `BGProcessingTask` (`photo-download` 5.4). */
    val scheduleBackstop: suspend () -> Unit = {},

    /** Selection snapshots under a partial grant (capability `limited-photo-access`); the inert default
     *  serves every composition that never sees one (world by default, desktop harnesses). */
    val selectionChanges: PhotoSelectionChangeSource = PhotoSelectionChangeSource.None,

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
            clock = ports.clock,
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
     *
     * Public for the same reason [gallery] is: the dev/test control channel's gallery read asks THIS seam
     * rather than walking PhotoKit itself, so what it reports is what the app would see — including the
     * grant-dependent backing. A second walk would be a second answer, and the interesting failures are
     * exactly the ones where the two would differ.
     */
    val candidates: CandidateSource by lazy {
        PermissionAwareCandidateSource(
            permission = ports.photoAccess.permission,
            walk = ports.candidateSource,
            selection = latestSelectionSnapshot,
        )
    }

    /**
     * The one presence source the download guard holds: full access queries the library, a partial grant
     * answers from the snapshot and never says "absent", no grant answers unknown (capability
     * `photo-download`). Built here for the same reason as [candidates] — choosing a source by the
     * grant is composition, and both halves are available here.
     */
    private val assetPresence: ImportedAssetPresence by lazy {
        PermissionAwareAssetPresence(
            permission = ports.photoAccess.permission,
            library = ports.assetPresence,
            selection = latestSelectionSnapshot,
        )
    }

    val gallery: OwnDeviceGalleryStatusSource by lazy {
        // The two exclusion readers moved to the one derivation below — this source now receives a
        // finished policy (capability `photo-selection-policy`).
        OwnDeviceGalleryStatusSource(candidates)
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
        // The staging root is read from the port that also releases those bytes, at first use rather
        // than at composition — on iOS it is an App-Group container lookup (capability `download-store`).
        QueuedPhotoDownloadJobs(
            scope = scope,
            stagingRoot = ports.stagedBytes.stagingRoot(),
            newTransport = ports.newDownloadTransport,
            // UIKit owns this session's completion handler and requires the main thread for it
            // (capability `ios-app-shell`); the harness binds its own lane.
            uiLane = ports.uiLane,
            logScope = ports.logScope,
        )
    }

    // The download orchestrator: union → foreign selection → download → import → suppression.
    val downloadController: DownloadController by lazy {
        val controller = DownloadController(
            union = ports.union,
            store = ports.downloadStore,
            jobs = downloadJobs,
            importer = ports.importer,
            presence = assetPresence,
            stagedBytes = ports.stagedBytes,
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
    /**
     * Kind → instance (capability `upload-lifecycle`, "The upload mechanism is resolved, never selected").
     *
     * Built **once**, and that is a platform requirement rather than an optimisation: the app-driven
     * mechanism owns a background `URLSession` whose identifier must stay stable for the OS to re-adopt
     * it across launches, and whose invalidation is terminal — an uncatchable `NSException` that aborts
     * the process (`ios-url-session-upload`, "Cancellation never invalidates the background session").
     * So resolving away from it and back returns the *same* instance; "instantiate" here means "obtain
     * the mechanism for this kind", never "construct a second one".
     *
     * On an OS that carries both, the app-driven cell is wrapped so it gives the OS back a registration
     * this process must not run behind. That is what makes the deregistration a consequence of the table
     * rather than a case to remember, and it serves the development override and a downgrade to a partial
     * grant with one rule instead of two.
     */
    private val uploadMechanisms: (UploadMechanism) -> UploadMechanismRuntime by lazy {
        val osDriven = ports.osDrivenUpload()
        val appDriven = ports.appDrivenUpload()
        // Each cell relinquishes what the OTHER mechanism left behind, because both leave state the OS
        // keeps across process death — a configuration record on one side, in-flight transfers and a
        // submitted background task on the other. Symmetric structure, deliberately asymmetric content:
        // giving up the OS-driven mechanism here is deregistration ONLY (its full stop would wipe ledger
        // rows the incoming mechanism is about to reconcile precisely), while giving up the app-driven
        // one is exactly its ordinary stop.
        val appDrivenHere =
            if (osDriven == null) appDriven else RelinquishThenRun(ports.relinquishOsRegistration, appDriven)
        val osDrivenHere = osDriven?.let { RelinquishThenRun({ appDriven.stop() }, it) }
        ({ kind ->
            when (kind) {
                // Unreachable unless this OS composed the mechanism: resolution clamps PHOTOKIT to what
                // the device can run, and `:test:architecture` asserts no cell escapes that clamp.
                UploadMechanism.PHOTOKIT -> osDrivenHere ?: appDrivenHere
                UploadMechanism.URL_SESSION -> appDrivenHere
                UploadMechanism.IDLE -> IdleUploadMechanism
            }
        })
    }

    // The tier-neutral upload lifecycle (capability `upload-lifecycle`): which verb fires on which
    // membership transition, over the mechanism resolution yields. The root defaults nothing — an absent
    // membership is `null`, and both the resolution rule and the transition table live in tested code.
    val uploadArm: UploadArm by lazy {
        UploadArm(
            // Read fresh at every transition: the mechanism is a function of runtime permission (the OS
            // never invokes the extension under a partial grant — measured; `ios-photokit-upload`), so a
            // captured answer would be stale exactly when it mattered.
            resolve = {
                resolveUploadMechanism(
                    backgroundUploadSupported = ports.osSupportsOsDrivenUpload,
                    permission = ports.photoAccess.permission.value,
                    override = ports.uploadMechanismOverride(),
                )
            },
            mechanismFor = uploadMechanisms,
            membershipIncludesUpload = { ports.configSource.config.value?.direction?.includesUpload },
            log = ports.log,
            logScope = ports.logScope,
        )
    }

    /**
     * The backend-leave effect the leave use-case and the switch path both fire (capability
     * `leave-event`) — the [LeaveNotifier] port wrapped as the `suspend (eventId) -> Unit` its two
     * consumers take. Built here because `flow/Provision` may not name a port at all (law "flow/ never
     * references ports/"), and `LeaveEvent` takes the same shape so the two paths cannot diverge.
     *
     * The port's failed [Result] is **logged, not propagated**: leaving is best-effort by contract and
     * the local teardown has already completed by the time this runs, so there is nothing to roll back.
     * Logging it is what keeps the accepted abandon-leak (a backend membership left in place) from being
     * silent — the drop used to be invisible at every layer (spec `module-architecture`, "Absence is
     * never silent").
     */
    private val notifyLeave: suspend (eventId: String) -> Unit = { eventId ->
        ports.leaveNotifier.notifyLeaving(eventId).onFailure { failure ->
            ports.log.w(failure) {
                "leave notify failed for $eventId — this device is gone locally; the backend membership " +
                    "remains until the sweep (the accepted abandon-leak)"
            }
        }
        Unit
    }

    // The leave use-case: stop the producer, clear the config (which flips the screen off the joined
    // layer), then notify the backend fire-and-forget. It destroys no dedup state.
    val leaveEvent: LeaveEvent by lazy {
        LeaveEvent(
            config = ports.configStore,
            configSource = ports.configSource,
            stopUploads = { uploadArm.onLeave() },
            scope = scope,
            notifyLeave = notifyLeave,
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
            now = { instantToCutoff(ports.clock.now()) },
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
        )
    }

    // The create-event use-case: mint via the backend, then route the minted event into the SAME
    // join gate a scanned QR takes (capability `photo-selection-policy`).
    val eventCreator: EventCreator by lazy {
        CreateEvent(
            client = ports.eventCreation,
            status = creationStatus,
            onMinted = ports.onEventMinted,
        )
    }

    /**
     * Voids this device's durable sync state (capability `device-state-reset`) so a build pointed at a
     * different backend starts from nothing. The cursor invalidation reuses the SAME
     * [AppPorts.clearDiscoveryCursor] effect `ReconfigureEvent` uses, so there is one surface for "make
     * the next cycle re-enumerate" rather than two that could diverge.
     *
     * Public because the dev/test control channel drives it directly. It has no user path by design —
     * `leave-event` deliberately keeps the ledger, and this is the one operation for which that reasoning
     * stops holding — so there is no command-bundle entry to reach it through.
     */
    val resetDeviceState: ResetDeviceState by lazy {
        ResetDeviceState(
            config = ports.configStore,
            ledger = ports.ledger,
            // Read-only here now: the reset reports how many imported rows SURVIVED, which is the number
            // that makes "imported rows were kept" verifiable rather than assumed.
            downloads = ports.downloadStore,
            // The download half is the CONTROLLER's, not the store's: the prune must run under the
            // controller's lock, because a ref is claimed under it and a reset that merely reads a
            // snapshot of what is claimed leaves a window for a claim in between — whose row is then
            // pruned, so its change block's marker write lands on nothing. Passing the critical section
            // rather than the value is also what keeps the membership feature blind to its sibling.
            resetDownloads = { downloadController.onDurableStateReset() },
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
    /**
     * The one derivation, for this composition's status readers (capability `photo-selection-policy`).
     *
     * Both `N` and the join preview must admit exactly what the upload cycle admits, so all three reach
     * the policy the same way — through `selectionPolicyFor`, with the same two port readers. The cycle
     * gets there via the membership's supplier; these two call it directly, because they already hold the
     * config and are already in a coroutine.
     */
    private suspend fun selectionPolicyForMembership(config: EventConfig): SelectionPolicy =
        selectionPolicyFor(
            config = config,
            suppressedAssetIds = { ports.downloadStore.suppressedLocalIds() },
            albumExcludedAssetIds = ports.albumExcludedAssetIds,
        )

    suspend fun refreshStatusSources() {
        // CHEAP LOCAL READS FIRST, the ~6 s library walk last (capability `sync-status`). Both counts
        // gate the screen out of its neutral first frame, and the walk is orders of magnitude slower
        // than the two SQLite reads — so doing the walk first published a counted total beside counts
        // nobody had read yet, and the screen briefly reported "0 of N".
        ledgerCounts.refresh()
        downloadStatusSource.refresh() // the "downloaded X of Y" line (capability `photo-download`)
        ports.configSource.config.value?.let { cfg ->
            // USABLE ACCESS, not GRANTED exactly. `candidates` is a `PermissionAwareCandidateSource`, so
            // where candidates come from is already its decision: GRANTED walks the library, LIMITED
            // filters the in-memory selection snapshot and issues NO library read at all. The read
            // discipline (`limited-photo-access`) is therefore intact either way — and re-stating the
            // grant here is exactly the consumer-side branch that source exists to remove.
            //
            // It also stopped being inert. While the total was seeded `0`, skipping the refresh under
            // LIMITED merely left a zero that happened to be right whenever the selection was empty. Now
            // that "not counted" is its own value, skipping leaves `N` UN-COUNTED for the whole session,
            // and a partial-grant member's screen would sit at "Syncing…" forever with nothing to say
            // why. Counting the selection — empty or not — is the honest answer and costs no round-trip.
            //
            // DENIED / NOT_DETERMINED still do not refresh: "nothing is readable" is not "nothing
            // qualifies" (see `PermissionAwareCandidateSource`), and the health is `NeedsAccess` there
            // regardless, which outranks every snapshot-derived value.
            if (ports.photoAccess.permission.value.grantsPhotoAccess) {
                // Bounded here, not thrown: this runs as one child of the Foreground flow's
                // `coroutineScope`, so an escaping failure would cancel its SIBLINGS — the download
                // reconcile, the staged-byte reclaim, the membership refresh — none of which have
                // anything to do with enumerating a library.
                //
                // NARROWED to the POLICY derivation. What the walk itself does on failure is the
                // source's own invariant now — it leaves `N` untouched and logs at Error severity
                // (capability `gallery-status`), so there is nothing left here to catch on its behalf.
                // What remains is this step's two port reads (echo suppression, the album denylist),
                // whose failure is a sibling-cancellation risk and nobody else's rule.
                runCatching { selectionPolicyForMembership(cfg) }
                    .onFailure { ports.log.e(it) { "gallery: policy read failed — N not refreshed" } }
                    .onSuccess { policy -> gallery.refresh(policy) }
            }
        }
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
            // Delivered unconditionally to whichever mechanism is resolved; the mechanism declines if it
            // has nothing to add (`upload-lifecycle`, "Triggers are delivered to the mechanism and
            // declined explicitly").
            //
            // This used to branch on permission here — GRANTED to the tier's pump, LIMITED to the
            // selection drain — and that branch was compensating for thunks that could not see the
            // permission. It said exactly one thing: on an OS carrying the OS-driven mechanism under a
            // full grant, do not pump, because the OS owns scheduling. That IS the resolution, so
            // resolving says it once instead. (The two pump entry points it chose between have identical
            // bodies; the choice was never between them.)
            pumpForeground = { uploadArm.triggers.onForeground() },
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
            // The upload receiver is no longer conditional here. It used to be a nullable thunk wrapped in
            // a `GRANTED`-exactly check, which made this fan-out an **invoker-gate** — sound only while it
            // enumerated everyone who might read, an enumeration a new mechanism or trigger invalidates in
            // silence (`upload-lifecycle`, "…never at the invoker"). Both guards now live in the tested
            // `UploadPushReceiver`, and a mechanism with nothing to do declines for its own stated reason.
            receivers = listOf(
                downloadPushReceiver::onSilentPush,
                { eventId -> uploadArm.triggers.onSilentPush(eventId) },
            ),
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
            notifyLeave = notifyLeave,
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

    /**
     * The **composition lane** this graph's scope runs on, taken from the scope itself rather than
     * named, so the two can never disagree (spec `module-architecture`, law "Dispatcher lanes are
     * fixed by the composition").
     *
     * Commands need it explicitly because the composition scope does NOT govern them: the
     * presentation container launches an `intent { }` on an unconfined dispatcher, so a command's
     * synchronous prefix runs on whichever thread fired it — the main thread, for a tap. A `suspend`
     * function that never actually suspends (synchronous PhotoKit XPC behind a `suspend` signature is
     * exactly that shape) then runs to completion there.
     */
    private val coreLane: CoroutineContext =
        scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext

    /**
     * A command the caller waits on, run on the composition lane. Used where the screen needs the
     * outcome in hand — the join gate's `commitJoin` returns whether it joined.
     */
    private suspend fun <T> awaitingOnCoreLane(
        name: String,
        params: String = "",
        result: (T) -> String = { "" },
        block: suspend () -> T,
    ): T = withContext(coreLane) {
        tapLog.invocation(ports.logScope, name, params, result = result) { block() }
    }

    /**
     * A fire-and-forget command, run on the composition lane. The tap returns at once and the outcome
     * rides a status read-model.
     *
     * The `invocation` wrap sits INSIDE the launch deliberately: wrapping the launcher instead would
     * time the hand-off rather than the work, which is how `← tap.create (1ms)` came to be logged
     * against a multi-second backend mint — the same false duration `hold-os-receipts-until-work-completes`
     * removed from the OS-callback side.
     */
    private fun detachedOnCoreLane(name: String, params: String = "", block: suspend () -> Unit) {
        scope.launch(coreLane) { tapLog.invocation(ports.logScope, name, params) { block() } }
    }

    /**
     * A command that presents platform UI, run on the main lane ([AppPorts.uiLane]). Fire-and-forget:
     * the outcome of a system sheet or prompt arrives through a read-model, never as a return value.
     */
    private fun onUiLane(name: String, block: suspend () -> Unit) {
        scope.launch(ports.uiLane) { tapLog.invocation(ports.logScope, name) { block() } }
    }

    val userCommands: UserCommands by lazy {
        UserCommands(
            // Leave: cancel in-flight downloads and drop non-terminal rows (imported photos stay;
            // suppression rows are permanent), then run the leave use-case (disable producer → notify
            // the backend it is leaving → clear config/producer). Imported foreign photos are never
            // touched.
            leave = {
                awaitingOnCoreLane<Unit>("tap.leave") {
                    downloadController.onLeaveOrSwitch()
                    leaveEvent.leave()
                }
            },
            // Create: mint via the backend; the use-case routes the minted event into the SAME join
            // gate a scanned QR takes (fire-and-forget; outcomes ride `creationStatus`).
            create = { name, startsAt, endsAt ->
                detachedOnCoreLane("tap.create") {
                    eventCreator.create(name, startsAt.at.iso, endsAt.at.iso)
                }
            },
            // The join gate's commit (capability `join-event`): enroll (register-only empty manifest)
            // then provision. `true` unless enrollment failed (the same-event no-op is a success).
            commitJoin = {
                eventId, name, startsAt, endsAt, deletesAt, minPhotoDate, maxPhotoDate, direction,
                saveToAlbum,
                ->
                awaitingOnCoreLane(
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
            share = { url -> onUiLane("tap.share") { ports.sharePresenter.share(url) } },
            // The permission user-taps (capability `permission-gate`), bound to the requester port here
            // so presentation never names it (migration step 9). `requestAccess` returns nothing and
            // cannot suspend — the grant arrives only via the permission read-model StateFlow.
            requestAccess = { onUiLane("tap.requestAccess") { ports.photoAccessRequester.request() } },
            openSettings = { onUiLane("tap.openSettings") { ports.photoAccessRequester.openSettings() } },
            // The picker presentation is platform surface; the selection outcome arrives only via
            // the selection-change seam (fire-and-forget, like every command here).
            choosePhotos = { onUiLane("tap.choosePhotos") { ports.photoAccessRequester.choosePhotos() } },
            // In-place membership reconfigure (capability `reconfigure-membership`): edit direction/
            // cutoff/album without leaving. Distinct from `openSettings` (the iOS system settings page).
            reconfigure = { eventId, direction, minPhotoDate, maxPhotoDate, saveToAlbum ->
                awaitingOnCoreLane<Unit>("tap.reconfigure", params = "eventId=$eventId") {
                    reconfigureEvent.reconfigure(eventId, direction, minPhotoDate, maxPhotoDate, saveToAlbum)
                }
            },
            // Rename the joined event (capability `event-rename`): unlike `reconfigure`, which edits only
            // this device's settings, this rewrites the SHARED event — every member picks the new name up
            // on their next foreground refresh. Fire-and-forget; the outcome rides `renameStatus`.
            rename = { eventId, name ->
                detachedOnCoreLane("tap.rename", params = "eventId=$eventId") {
                    renameEvent.rename(eventId, name)
                }
            },
            // Clear the rename latch once the screen has consumed a terminal status. Instrumented like
            // the taps even though it is a screen-fired acknowledgement rather than a tap: it mutates
            // the rename lifecycle, and an unattributed state change is the thing this trail exists to
            // eliminate.
            resetRename = { awaitingOnCoreLane<Unit>("tap.resetRename") { renameEvent.reset() } },
            // The hidden diagnostic dump (capability `diagnostic-logging`), fired once the operator has
            // written what went wrong. NULL on a build with no reporting configuration, so the screen
            // wires no gesture and no sheet can open — a build that can send nothing must not offer an
            // affordance suggesting it can. This is the ONE place that decision is made.
            sendDiagnostics = if (ports.diagnosticsReporter.isConfigured) {
                { note, screen ->
                    // Core lane and awaited: the dump reads both device logs (~700 KB) before it sends,
                    // which is exactly the blocking work the main lane must never see, and the sheet
                    // waits on it.
                    awaitingOnCoreLane<Unit>("tap.sendDiagnostics", params = "screen=$screen") {
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
                ports.configSource.config.value?.let { cfg -> gallery.refresh(selectionPolicyForMembership(cfg)) }
                uploadArm.triggers.onSelectionChanged()
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
        scope.launch {
            // THE ONE ADJUDICATION CALL SITE (capability `photo-download`). Once per process, here, and
            // nowhere else — not in `reconcile`, not in `importReady`, not in `onResourceStaged`. Only a
            // process that DIED can leave a row no running import will settle, so a running process asking
            // again about rows it opened itself buys nothing and costs a synchronous XPC round-trip each
            // time: 1,149 discarded verdicts in one measured burst.
            //
            // Ordered INSIDE this method, after the subscriptions above, rather than left to the shell to
            // sequence: the requirement is that the presence source can answer when the sweep asks, and a
            // convention the shell has to honour is not a guarantee.
            //
            // Under a PARTIAL grant the answer comes from `latestSelectionSnapshot`, which is null until the
            // observer's first emission and yields UNKNOWN for every row until then. With one sweep per
            // process and no re-arm, a sweep that ran first would defer every inherited row to the next
            // launch — and on a URLSession-driven relaunch that never foregrounds, potentially every launch.
            // So under that grant it waits for the snapshot rather than asking a question the source cannot
            // answer yet. If the emission never comes the sweep never runs, which costs the same deferral
            // without the wasted lookup.
            if (ports.photoAccess.permission.value == PermissionStatus.LIMITED) {
                latestSelectionSnapshot.filterNotNull().first()
            }
            downloadController.sweepInterruptedImports()
        }
    }

    /**
     * Start registering the device's APNs token, and keep the registration alive across a credential
     * change (capability `push-registration`). Invoked from the shell's host-assembly path, beside
     * [installPermissionSubscriptions].
     *
     * **ATTEST FIRST.** `PUT /devices/<id>` is gated, and on a fresh install the APNs token can arrive
     * before this device has attested at all — measured on the SE2, where that `PUT` took a `401`.
     * Awaiting a refresh first removes the race.
     *
     * **THE `tokenChanged` ARM IS THE POINT, and it is a JOIN BETWEEN TWO BLIND FEATURES** — trust emits
     * that a new credential exists, push consumes it. Neither knows the other, and the join is the whole
     * recovery path for a registration the backend refused: the device writes its registration once per
     * APNs token the OS delivers, so without this a refused `PUT` waits for the next launch to be retried
     * — no silent pushes, no download wakes, and none of the wake-driven attestation renewals that
     * depend on them until then.
     *
     * That is why it lives HERE rather than in the shell. A join is behaviour, not wiring; assembled in
     * `:app:*` it is untested by law and invisible to the world harness, so nothing would observe it being
     * removed. Composed here, the same call the device makes is the one the harness makes.
     *
     * The registration and the token source are passed in rather than built: both are platform-shaped
     * (a Ktor client over the shell's shared HTTP stack, and the compile-time APNs environment), and
     * `:domain` builds no platform object.
     */
    fun installPushRegistration(registration: PushRegistration, tokens: PushTokenSource) {
        scope.launch {
            runCatching { attestation.ensureFresh() }
            registration.run(tokens, attestation.tokenChanged)
        }
    }
}

/**
 * The ONE app-graph composition (spec `module-architecture`, "One shared composition"): the app
 * shell calls this — and, at migration step 10, the world harness does too — so a wiring difference
 * between binaries is impossible rather than undetected. Manual DI (decision D6 of
 * `establish-target-architecture`): plain constructors, no framework.
 */
fun snapSyncApp(
    scope: CoroutineScope,
    ports: AppPorts,
): AppCore = AppCore(scope, ports)
