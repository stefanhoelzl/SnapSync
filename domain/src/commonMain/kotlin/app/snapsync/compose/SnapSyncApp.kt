package app.snapsync.compose

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.creation.CreateEvent
import app.snapsync.feature.creation.EventCreator
import app.snapsync.feature.creation.MutableCreationStatusSource
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.DownloadPushReceiver
import app.snapsync.feature.download.DownloadStatusSource
import app.snapsync.feature.download.QueuedPhotoDownloadJobs
import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.feature.membership.EventName
import app.snapsync.feature.membership.JoinEvent
import app.snapsync.feature.membership.JoinOutcome
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.feature.membership.ManifestDeviceEnroller
import app.snapsync.feature.status.LedgerBackedSyncStatusSource
import app.snapsync.feature.status.LedgerCounts
import app.snapsync.feature.status.LedgerCountsPoller
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.feature.status.ReadingLedgerCountsSource
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
import app.snapsync.model.Contribution
import app.snapsync.model.EventConfig
import app.snapsync.model.PermissionStatus
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
import app.snapsync.ports.ConfigStore
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.EventCreation
import app.snapsync.ports.EventDetails
import app.snapsync.ports.EventDirectory
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.Enrollment
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.LogScope
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.PhotoLibrary
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.PhotoSelectionChangeSource
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
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
    val photoLibrary: PhotoLibrary,
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
    val eventCreation: EventCreation,
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
    val albumExcludedAssetIds: suspend (cutoff: String) -> Set<String>,
    val notifyLeave: suspend (eventId: String) -> Unit,
    val provision: suspend (EventConfig) -> Unit,
    val onEventMinted: suspend (eventId: String) -> Unit,
    // ── Shell/platform effect lambdas the `flow/` triggers coordinate over (migration step 8) ──
    // Each is a port/platform touch a flow may not make directly (law "flow/ never references ports/"):
    // the shell supplies it here and `compose/` passes it to the flow. All default to inert for the
    // world harness / tests, which drive the features directly and never mount a flow.
    /** Renew the attestation token if stale — a wake point (`device-attestation`). */
    val refreshAttestation: () -> Unit = {},
    /** Re-read the persisted membership into the config StateFlow (migration step 12: every trigger
     *  flow re-reads before acting — cross-process writes and a pre-first-unlock seed never notify
     *  this process's StateFlow). The default is inert: world/tests hold their config in-process. */
    val reloadConfig: () -> Unit = {},
    /** Pump the app-driven upload tier on foreground; a no-op on iOS ≥26.1 (`ios-url-session-upload`). */
    val pumpForeground: () -> Unit = {},
    /** Queue the download import-tail backstop `BGProcessingTask` (`photo-download` 5.4). */
    val scheduleBackstop: () -> Unit = {},
    /** Present the platform share surface for the invite URL (`UIActivityViewController` on iOS) —
     *  the platform half of the [UserCommands.share] command; the default keeps it inert off-device. */
    val share: (String) -> Unit = {},
    /** The upload arm's silent-push receiver on the app-driven tier, or `null` on iOS ≥26.1. A thunk so
     *  the tier controller (which depends on this graph) resolves lazily, never at composition time. */
    val uploadSilentPush: () -> (suspend (eventId: String) -> Unit)? = { null },
    /** Selection snapshots under a partial grant (capability `limited-photo-access`); the inert default
     *  serves every composition that never sees one (world by default, desktop harnesses). */
    val selectionChanges: PhotoSelectionChangeSource = PhotoSelectionChangeSource.None,
    /** Drive one app-driven upload cycle for a selection change (the pump's `onSelectionChanged`),
     *  wired by the shell to the tier controller; inert where no app-driven tier exists. */
    val pumpSelectionChanged: () -> Unit = {},
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
    val gallery: OwnDeviceGalleryStatusSource by lazy {
        OwnDeviceGalleryStatusSource(
            ports.photoLibrary,
            suppressedLocalIds = { ports.downloadStore.suppressedLocalIds() },
            albumExcludedAssetIds = ports.albumExcludedAssetIds,
        )
    }

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
        downloadJobs.onStaged = { ref, key, path -> scope.launch { controller.onResourceStaged(ref, key, path) } }
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
            notifyLeave = ports.notifyLeave,
            scope = scope,
        )
    }

    // The join use-case (capability `join-event`): fetch details, enroll by writing the register-only
    // EMPTY device manifest, then provision through the same path as create/scan.
    val joinEvent: JoinEvent by lazy {
        JoinEvent(
            configSource = ports.configSource,
            deviceId = ports.deviceId,
            details = ports.directory,
            enroller = ManifestDeviceEnroller(ports.enrollment),
            provision = ports.provision,
        )
    }

    // The event-name refresh rule (capability `join-event`): whether a fetched name is persisted —
    // seated in `feature/membership` because the membership config is that feature's durable state.
    // The *fetch* it pairs with is [fetchEventName], coordinated by the Foreground/Provision flows.
    val eventName: EventName by lazy {
        EventName(ports.configSource, ports.configStore)
    }

    // The best-effort `GET /events/:id` name fetch (`null` on offline / 404 / parse) — the
    // `EventDirectory` port effect the flows coordinate over, built here because a flow may not
    // touch a port directly (law "flow/ never references ports/").
    private val fetchEventName: suspend (eventId: String) -> String? = { eventId ->
        (ports.directory.fetch(eventId) as? EventDetails.Found)?.name
    }

    /** The create-event status the use-case drives and the container reads (same instance). */
    val creationStatus: MutableCreationStatusSource = MutableCreationStatusSource()

    // The create-event use-case: mint via the backend, then route the minted event into the SAME
    // join gate a scanned QR takes (capability `photo-selection-policy`).
    val eventCreator: EventCreator by lazy {
        CreateEvent(
            client = ports.eventCreation,
            status = creationStatus,
            onMinted = ports.onEventMinted,
            scope = scope,
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
                gallery.refresh(Contribution.of(cfg.direction.includesUpload, cfg.minPhotoDate))
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
            scope = scope,
            downloadController = downloadController,
            eventName = eventName,
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
            fetchEventName = fetchEventName,
            refreshAttestation = ports.refreshAttestation,
        )
    }

    val backgroundFlow: Background by lazy {
        Background(statusPoller = ledgerCountsPoller, scheduleBackstop = ports.scheduleBackstop)
    }

    val silentPushFlow: SilentPush by lazy {
        SilentPush(
            scope = scope,
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
            scope = scope,
            downloadController = downloadController,
            reloadConfig = ports.reloadConfig,
            refreshAttestation = ports.refreshAttestation,
        )
    }

    val provisionFlow: Provision by lazy {
        Provision(
            scope = scope,
            uploadArm = uploadArm,
            downloadController = downloadController,
            albumCoordinator = albumCoordinator,
            eventName = eventName,
            activeEventId = { ports.configSource.config.value?.eventId },
            notifyLeave = ports.notifyLeave,
            saveConfig = { cfg -> ports.configStore.save(cfg) },
            refreshStatus = { refreshStatusSources() },
            // Usable access (`grantsPhotoAccess`): this gate feeds only ensureAlbum's granted
            // parameter, and album creation works under a LIMITED grant (measured — capability
            // `limited-photo-access`).
            isGranted = { ports.photoAccess.permission.value.grantsPhotoAccess },
            fetchEventName = fetchEventName,
        )
    }

    // ── The user-tap command bundle (spec `module-architecture`, "Commands cross one door"): built and
    // decorated only here in `compose/`, injected into `StatusContainerHost` by constructor — so
    // presentation never references a feature command directly. Each command's body is the exact
    // coordination the shell's individual lambdas used to carry (migration step 8 C3). ────────────────

    val userCommands: UserCommands by lazy {
        UserCommands(
            // Leave: cancel in-flight downloads and drop non-terminal rows (imported photos stay;
            // suppression rows are permanent), then run the leave use-case (disable producer → notify
            // the backend it is leaving → clear config/producer). Imported foreign photos are never
            // touched.
            leave = {
                downloadController.onLeaveOrSwitch()
                leaveEvent.leave()
            },
            // Create: mint via the backend; the use-case routes the minted event into the SAME join
            // gate a scanned QR takes (fire-and-forget; outcomes ride `creationStatus`).
            create = { name, startsAt -> eventCreator.create(name, startsAt) },
            // The join gate's commit (capability `join-event`): enroll (register-only empty manifest)
            // then provision. `true` unless enrollment failed (the same-event no-op is a success).
            commitJoin = { eventId, name, startsAt, minPhotoDate, direction, saveToAlbum ->
                joinEvent.join(eventId, name, startsAt, minPhotoDate, direction, saveToAlbum) !=
                    JoinOutcome.EnrollFailed
            },
            // Share is pure platform (a system sheet over the top view controller) — the shell's lambda,
            // passed through undecorated.
            share = ports.share,
            // The permission user-taps (capability `permission-gate`), bound to the requester port here
            // so presentation never names it (migration step 9). `requestAccess` returns nothing and
            // cannot suspend — the grant arrives only via the permission read-model StateFlow.
            requestAccess = { ports.photoAccessRequester.request() },
            openSettings = { ports.photoAccessRequester.openSettings() },
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
            // the cell feeds the cycle's discovery, `refreshFrom` recounts N over the same list, and
            // the pump drains — no second library read anywhere on this path.
            ports.selectionChanges.snapshots.collect { snapshot ->
                latestSelectionSnapshot.value = snapshot
                ports.configSource.config.value?.let { cfg ->
                    gallery.refreshFrom(snapshot, Contribution.of(cfg.direction.includesUpload, cfg.minPhotoDate))
                }
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
