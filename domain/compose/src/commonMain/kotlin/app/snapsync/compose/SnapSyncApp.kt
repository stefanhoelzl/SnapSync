package app.snapsync.compose

import app.snapsync.services.gallery.PermissionAwareCandidateSource

import app.snapsync.services.gallery.PermissionAwareAssetPresence

import app.snapsync.model.AssetId
import app.snapsync.model.VersionRefusal
import app.snapsync.model.runCatchingCancellable
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.album.AlbumGather
import app.snapsync.feature.creation.CreateEvent
import app.snapsync.model.EventCreator
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.feature.diagnostics.CollectDiagnosticDump
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.DownloadPushReceiver
import app.snapsync.services.downloads.DownloadJobs
import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.feature.membership.MembershipRefresh
import app.snapsync.feature.membership.toJoinLoad
import app.snapsync.feature.push.PushRegistration
import app.snapsync.feature.membership.JoinEvent
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.feature.membership.ShareSetLoad
import app.snapsync.feature.membership.ManifestDeviceEnroller
import app.snapsync.feature.membership.readmodel.RenameStatus
import app.snapsync.feature.membership.ReconfigureEvent
import app.snapsync.feature.membership.RenameEvent
import app.snapsync.feature.membership.ResetDeviceState
import app.snapsync.feature.status.LedgerBackedSyncStatusSource
import app.snapsync.feature.status.LedgerCounts
import app.snapsync.feature.status.StatusCountsPoller
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.feature.status.ReadingLedgerCountsSource
import app.snapsync.feature.status.ShareableCountSource
import app.snapsync.feature.status.StatusRefresh
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.services.trust.DeviceAttestation
import app.snapsync.services.version.AppVersionGate
import app.snapsync.feature.upload.PushTailGuard
import app.snapsync.feature.upload.TailTrigger
import app.snapsync.services.upload.ExtensionRegistration
import app.snapsync.services.upload.OsDrivenRegistration
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.feature.upload.UploadAdmission
import app.snapsync.feature.upload.UploadTransitions
import app.snapsync.feature.upload.appAdmission
import app.snapsync.model.extensionRegistrable
import app.snapsync.flow.Background
import app.snapsync.flow.Foreground
import app.snapsync.flow.Provision
import app.snapsync.flow.SilentPush
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionPolicyFor
import app.snapsync.model.SelectionSnapshot
import app.snapsync.model.resourcesFrom
import kotlinx.coroutines.channels.Channel
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinLoad
import app.snapsync.model.GalleryAccess
import app.snapsync.model.Resource
import app.snapsync.model.SelectionScope
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.ports.BackgroundTime
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.gallery.GalleryAccessState
import app.snapsync.services.gallery.GalleryImporter
import app.snapsync.services.gallery.CandidateSource
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.services.gallery.ImportedAssetPresence
import app.snapsync.services.gallery.GalleryAssetPresence
import app.snapsync.services.gallery.GalleryCandidateSource
import app.snapsync.ports.Backend
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.services.backend.BackendServices
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.SystemUi
import app.snapsync.model.invocation
import app.snapsync.ports.ProcessInfo
import app.snapsync.ports.Wake
import app.snapsync.ports.DevControls
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.Links
import app.snapsync.ports.Ui
import app.snapsync.ports.Download
import app.snapsync.ports.Upload
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.Databases
import app.snapsync.ports.Port
import app.snapsync.ports.Preferences
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.SecureStore
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * **The app process's ports** — everything the app-graph composition consumes, and nothing but ports ([Port]; spec
 * `docs/architecture.md`, "One shared composition"). The root constructs its platform adapters and supplies them here;
 * [snapSyncHost][app.snapsync.compose] composes the process ([ProcessPorts] first — its crash reporting before
 * anything else), then the services over these ports ([AppServices]), then the feature graph.
 *
 * No defaults: a port added here is added once, and every root fails to compile until it answers — the phone's, the
 * JVM's and the rig's alike. What used to ride here beside the ports — the services a root built, the build's
 * constants, the coordination lambdas, the main lane — is built by the composition itself, read from [BuildInfo], or
 * owned by the adapter that needs it (a platform-UI adapter reaches its own main thread).
 */
class AppPorts(
    /** The ports every process has exactly one of: crash reporter, log sinks, files, clock, build. */
    val process: ProcessPorts,
    /** The app's SQLite databases: the ledger and the download store open on them, on first use. */
    val databases: Databases,
    /** Small shared preferences: the event album's leave-surviving map. */
    val preferences: Preferences,
    /** The protected small-value store: the device id, the attestation token and key id, the legacy album map. */
    val secureStore: SecureStore,
    /** The platform's own stable device id, where it has one — what a first mint adopts. */
    val platformDeviceId: PlatformDeviceId,
    /** The photo-library grant, as a state the core observes. */
    val photoAccess: PhotoAccessStatusSource,
    /**
     * The device's photo library (`docs/architecture.md`): every read the status total, the join preview, the
     * download guard and the event album make, the album writes, and the `requestAccess` / `choosePhotos`
     * user taps. The decisions over it — which grant may answer, what is denylisted — are the services this
     * graph builds over it, never the gallery's.
     */
    val gallery: Gallery,
    /** The platform's own UI, where this app hands something over and stops being involved — the share sheet
     *  for the invite URL, the URL opener for the store link, and the app's Settings page (see [SystemUi]). */
    val systemUi: SystemUi,
    /**
     * The platform's background downloads (capability `receiving-photos`). An event port: the host zone registers
     * this core's [AppEvents.downloadHandlers] on it as the graph is composed, so a background relaunch that delivers
     * finished transfers finds them.
     */
    val download: Download,
    /**
     * The backend (`docs/architecture.md`, "Ports are the I/O boundary named for the need") — production passes
     * `:adapter:generic:app`'s `HttpBackend` over the platform's HTTP client. Every need-shaped backend service is
     * composed over it here, behind ONE authenticated backend, so no root can wire a call without the credential or
     * without the version verdicts.
     */
    val backend: Backend,
    /** The platform's device-integrity service (App Attest on iOS) — what the attestation service proves with. */
    val integrity: DeviceIntegrity,
    /**
     * The app's own uploader transport (a background `URLSession` on iOS) — what the app uploader's cycle creates jobs
     * on, and an event port: the host zone registers this core's [AppEvents.uploadHandlers] on it as the graph is
     * composed, so a relaunch that hands back finished uploads records them.
     */
    val appUpload: Upload,
    /** The process's background time (`docs/architecture.md`, "Background time is an outbound port named for
     *  the need"): what a push or a transfer wake holds across its own work and its tail, and the only "time is up"
     *  those wakes get. */
    val backgroundTime: BackgroundTime,
    /**
     * The operating system's scheduled wakes (`docs/architecture.md`, "Background execution"): the heartbeat the tail
     * runner re-arms, and — through its `listen`, registered as the graph is composed — the wakes it delivers.
     */
    val wake: Wake,
    /** The OS's record of the upload extension's registration — present on every platform; one without the
     *  OS-driven mechanism (iOS below 26.1, the JVM, Android) answers `Unsupported` and asks the OS nothing. The
     *  disable→enable ritual over it is composed here ([AppCore.extensionRegistration]). */
    val extensionRegistry: ExtensionRegistry,
    /** The runtime inputs only a rig build can set — the per-uploader pin, the invite-link hints and the reset. All
     *  inert in a production build (see [DevControls]). */
    val devControls: DevControls,
    /** The platform's push service (capability `receiving-photos`): asked for the token at launch and at every
     *  foreground entry; the host zone registers this core's [pushHandlers] on it as the graph is composed. */
    val pushNotifications: PushNotifications,
    /** The app's foreground life — an event port the host zone registers the foreground work on. */
    val lifecycle: Lifecycle,
    /** The links the platform opens the app with — an event port the host zone registers the link handler on. */
    val links: Links,
    /** The platform's user interface — shown every state the status host reduces, from host assembly on. */
    val ui: Ui,
    /** What the OS says about this process — whether protected storage is readable right now, recorded by the
     *  background entry points and deciding nothing (capability `sync-status`). */
    val processInfo: ProcessInfo,
)

/**
 * The composed app graph. Every property is `by lazy`, mirroring the composition root's previous
 * lazy web **byte-for-byte in construction timing**: nothing here resolves the device identity or
 * touches a platform store until the same first-use moment the root's own lazies did — the property
 * a locked background launch depends on.
 */
class AppCore internal constructor(
    internal val scope: CoroutineScope,
    /** What the process set up before this core existed — its crash reporting already started. */
    val process: ProcessServices,
    internal val ports: AppPorts,
    /** Where a minted event goes — the host zone's join gate, so create and a scanned QR take one gate. */
    private val onEventMinted: suspend (eventId: String) -> Unit,
) {

    /** This process's services over [ports] — built here, never by a root (see [AppServices]). */
    internal val services: AppServices = AppServices(ports, process)

    /** The composition's own log lines. */
    internal val log get() = services.log

    /**
     * The app's uploader (capability `background-upload`) — the app-driven tier on every OS version, over the app's own
     * [AppPorts.appUpload]. Always the real one: a composition whose transport is mocked is driven at that transport.
     */
    internal val appUploader: AppUploader by lazy { AppUploader(this) }

    /**
     * Whether the backend is refusing this build as too old (capability `app-update-required`). NOT lazy: the
     * status host observes it from its first frame, and a lazy cell created by a late reader could miss nothing
     * today but would invite a writer that captured an earlier one.
     */
    val versionGate: AppVersionGate = AppVersionGate()

    /**
     * Device attestation (capability `privacy-security`) — the bearer token EVERY backend call
     * carries, and the app's recovery when the backend rejects it. Composed here; only the app process can
     * attest (`DCAppAttestService.isSupported` is false in an app extension — measured), and the extension
     * reads the token this writes.
     */
    val attestation: DeviceAttestation by lazy { attestationFor(services, process.clock, versionGate) }

    /** [versionGate]'s cell, for readers outside the core (the status host), which see no service type. */
    val versionRefusal: StateFlow<VersionRefusal?> get() = versionGate.refusal

    /** [attestation]'s health cell, for readers outside the core (the status host), which see no service type. */
    val attested: StateFlow<Boolean> get() = attestation.attested

    /**
     * Every need-shaped backend service, over one authenticated backend whose credential is [attestation]
     * (capability `privacy-security`: a rejected token is dropped, a new one obtained, and the call retried once)
     * and whose verdicts reach [versionGate]. Public for the app root's uploader, which publishes its manifest
     * through [BackendServices.manifest] like every other caller.
     */
    val backend: BackendServices by lazy { backendServicesFor(services, attestation, versionGate) }

    // Own-device completeness AND in-flight, both from one consistent per-photo `assetProgress()` read
    // (capability `sync-status`), counted by the status source over the gallery's admitted set. Read-only;
    // on any failure the last good value is retained.
    val ledgerCounts: ReadingLedgerCountsSource by lazy {
        ReadingLedgerCountsSource { LedgerCounts.of(services.ledger.assetProgress()) }
    }

    /**
     * The one read seam the app's consumers hold — the grant decides the backing, not the consumer
     * (capability `photo-access`). Built here because choosing between ports by a third port's
     * state is composition.
     *
     * Public for the same reason [gallery] is: the dev/test control channel's gallery read asks THIS seam
     * rather than walking PhotoKit itself, so what it reports is what the app would see — including the
     * grant-dependent backing. A second walk would be a second answer, and the interesting failures are
     * exactly the ones where the two would differ.
     */
    val candidates: CandidateSource by lazy {
        PermissionAwareCandidateSource(
            permission = galleryAccess.grant,
            walk = GalleryCandidateSource(ports.gallery),
            selection = latestSelectionSnapshot,
        )
    }

    /**
     * The one presence source the download guard holds: full access queries the library, a partial grant
     * answers from the snapshot and never says "absent", no grant answers unknown (capability
     * `receiving-photos`). Built here for the same reason as [candidates] — choosing a source by the
     * grant is composition, and both halves are available here.
     */
    private val assetPresence: ImportedAssetPresence by lazy {
        PermissionAwareAssetPresence(
            permission = galleryAccess.grant,
            library = GalleryAssetPresence(ports.gallery),
            selection = latestSelectionSnapshot,
        )
    }

    // The own-device upload TOTAL N (capability `sync-status`): gallery enumeration minus downloaded
    // foreign photos, scoped by the membership's cutoff and the origin exclusions
    // (capability `photo-sharing`) — the SAME policy the upload cycle gets, because the
    // two enumerate independently and a rule applied to one and not the other would peg the joined
    // screen below 100% forever.
    val gallery: OwnDeviceGalleryStatusSource by lazy { OwnDeviceGalleryStatusSource(candidates) }

    // The join-time shareable-count preview (capability `join-event`): the SAME policy the cycle and
    // `gallery` (N) apply, over the same permission-aware source — so the preview and the total cannot
    // disagree about where candidates come from. No usable grant → null → the surface omits the row.
    private val shareableCountSource: ShareableCountSource by lazy {
        ShareableCountSource(
            source = candidates,
            suppressedLocalIds = { services.downloadStore.suppressedLocalIds() },
            // The same grant-gated reader the status total uses — the preview runs on the JOIN surface,
            // where an unresolved grant is the normal state, so an ungated read would prompt there too.
            albumExcludedAssetIds = ::albumExclusionsWhenReadable,
        )
    }

    /**
     * The join surface's live "how many photos from your gallery will be shared" query (capability
     * `join-event`): for a candidate [cutoff] with sharing on, the count of own photos the policy
     * admits, or `null` when the grant permits no count. Purely local — no backend LIST.
     */
    suspend fun loadShareableCount(cutoff: CaptureCutoff, until: CaptureCeiling?): Int? =
        shareableCountSource.count(includesUpload = true, cutoff = cutoff, ceiling = until)

    /** What the photo-library grant means now, over the permission port — the one read every feature takes. */
    val galleryAccess: GalleryAccessState by lazy { GalleryAccessState(ports.photoAccess) }

    /** The membership's cell, for readers outside the core (the status host), which see no service type. */
    val membership: StateFlow<EventConfig?> get() = services.config.config

    /** The photo-access grant, exposed for the join surface's count-recompute trigger (a late resolve). */
    val photoPermission: StateFlow<GalleryAccess> get() = galleryAccess.grant

    /** The real ledger-backed status source (ledger truth × permission × gallery total). */
    val syncStatusSource: SyncStatusSource by lazy {
        LedgerBackedSyncStatusSource(ledgerCounts, galleryAccess, gallery.admitted, scope)
    }

    // Download progress for the joined screen's received count and download arrow, scoped to the joined event
    // (capabilities `receiving-photos`, `sync-status`).
    val downloadStatusSource: StoreDownloadStatusSource by lazy {
        StoreDownloadStatusSource(services.downloadStore, currentEvent = { services.config.config.value?.eventId })
    }

    // Background byte transfers → durable staging. The queue, bounded window, and cancellation
    // lifecycle live in the tested feature; the transport is the shell's adapter thunk.
    val downloadJobs: DownloadJobs by lazy {
        // Staging is the port that also releases those bytes, so the two never name different directories
        // (capability `receiving-photos`); the jobs record relative paths.
        DownloadJobs(
            scope = scope,
            staging = services.stagedBytes,
            download = ports.download,
            // Deliver each staged resource back to the controller — a compose-built lambda whose body is one call (law
            // "Commands cross one door"). It reads the `downloadController` lazy when INVOKED, not here: the jobs are
            // built on paths that build nothing else — a background-`URLSession` relaunch touches only `downloadJobs` —
            // and the callback must reach the controller on those paths too (capability `receiving-photos`, "A staged
            // resource reaches the controller on every entry point"). No launch here: the jobs own it, so they can
            // join the stagings before the session's OS handler is released.
            // Staging is recorded here and the import is the tail's: requested detached — a staging holds no OS
            // handler of its own, and a wake that delivered it requests (and holds) its own tail after its drain.
            onStaged = { ref, key, path ->
                val recorded = downloadController.onResourceStaged(ref, key, path)
                if (recorded) tail.requestDetached(TailTrigger.DOWNLOAD_STAGED)
            },
            entryContext = process.entryContext,
        )
    }


    // The download orchestrator: union → foreign selection → download → import → suppression.
    val downloadController: DownloadController by lazy {
        DownloadController(
            union = backend.union,
            store = services.downloadStore,
            jobs = downloadJobs,
            importer = GalleryImporter(ports.gallery, services.stagedBytes),
            presence = assetPresence,
            // The import-time album: the membership's opt-in gate is the coordinator's rule (capability
            // `event-album`); this only reads the current membership's facts.
            eventAlbum = {
                services.config.config.value?.let { albumCoordinator.albumIdFor(it.eventId, it.saveToAlbum) }
            },
            onImportedIntoAlbum = { album ->
                services.config.config.value?.let { albumCoordinator.onImportedInto(it.eventId, album) }
            },
            stagedBytes = services.stagedBytes,
            myDeviceId = services.deviceIdentity.deviceId(),
            // Three-valued, no fallback (capability `receiving-photos`): no membership → `null` → no arm.
            downloadEnabled = { services.config.config.value?.direction?.includesDownload },
            checks = services.eventChecks,
            readyToImport = { importsReady() },
            entryContext = process.entryContext,
        )
    }

    // The silent-push receiver for the download arm (capability `receiving-photos`); its active-event
    // guard is the feature's rule. The app shell fans this out with the upload arm's receiver until
    // the fan-out re-homes (step 8).
    val downloadPushReceiver: DownloadPushReceiver by lazy {
        DownloadPushReceiver(
            configSource = services.config,
            controller = downloadController,
        )
    }

    /** The album operations over the gallery (capabilities `event-album`, `photo-sharing`). */
    private val albumManager: GalleryAlbums by lazy { GalleryAlbums(ports.gallery) }

    // Event album (capability `event-album`): the coordinator over the shared leave-surviving map.
    // The APP is the SOLE creator (on the permission grant); both processes only add.
    val albumCoordinator: AlbumCoordinator by lazy {
        AlbumCoordinator(albumManager, services.albumMapStore, kind = ports.gallery.albumKind)
    }

    // The event album's gather (capability `event-album`): place what the device already holds for the event.
    // Built HERE and nowhere in the extension's graph, so "the extension never gathers" holds by
    // construction. Started, never awaited, by the act that triggered it.
    val albumGather: AlbumGather by lazy {
        albumGather(
            services, process.entryContext, galleryAccess, albumCoordinator, scope,
            ::selectionPolicyForMembership,
        )
    }

    /**
     * Whether the upload extension may be registered now (capability `background-upload`, "Whether the extension
     * may be registered is one pure fact") — read fresh wherever it is needed, never held: it is a function of
     * the runtime grant, so a captured answer would be stale exactly when it mattered.
     */
    val extensionRegistrableNow: () -> Boolean = {
        extensionRegistrable(
            osSupportsOsDrivenUpload = ports.process.build.osSupportsOsDrivenUpload,
            permission = ports.photoAccess.permission.value,
            pin = ports.devControls.uploaderPin(),
        )
    }

    /**
     * Whether the app's uploader may create now — the app process's admission, which its entry gate consumes
     * (capability `background-upload`, "The upload cycle owns its entry decision").
     */
    val appUploadAdmission: () -> UploadAdmission = {
        // The scope, not the raw cell: an unread partial-grant selection withholds (`appAdmission`), and it is
        // the same derivation discovery reads through `selectionScope()`.
        appAdmission(ports.photoAccess.permission.value, selectionScope(), ports.devControls.uploaderPin())
    }

    /** [appUploadAdmission] as a Boolean — what the app pump's completion re-pump reads (capability
     *  `background-upload`, "The delegate records the terminal fact before it returns"). */
    val appMayCreate: () -> Boolean = { appUploadAdmission() == UploadAdmission.Admit }

    // The upload arm (capability `background-upload`): what each membership transition does to the two
    // uploaders. Stateless — every decision is derived from the registration fact, the grant and whether a
    // membership exists, at the moment of the transition; the root defaults nothing.
    /** The OS-driven mechanism's registration ritual, over the registry port (capability `background-upload`). */
    val extensionRegistration: ExtensionRegistration by lazy {
        OsDrivenRegistration(ports.extensionRegistry, services.log, process.entryContext)
    }

    val uploadTransitions: UploadTransitions by lazy {
        UploadTransitions(
            configSource = services.config,
            photoAccess = galleryAccess,
            extensionRegistrable = extensionRegistrableNow,
            registration = extensionRegistration,
            appEngine = { tail.appEngine },
            log = services.log,
            entryContext = process.entryContext,
        )
    }

    /**
     * The backend-leave effect the leave use-case and the switch path both fire (capability `manage-membership`),
     * recorded and delivered through [PendingLeaves] — see [membershipEnd]. Built here because `flow/Provision` may
     * not name a port or a service at all (law "flow/ never references ports/").
     */
    private val notifyLeave: suspend (eventId: String) -> Unit = { eventId -> membershipEnd.notifyLeave(eventId) }

    /** How a membership ends on its own, and how a leave reaches the backend — see [MembershipEnd]. */
    val membershipEnd: MembershipEnd by lazy { MembershipEnd(this) }

    // The leave use-case: stop the producer, clear the upload ledger (the ledger is the current
    // membership's share set — capability `photo-sharing`), clear the config (which flips the screen off the
    // joined layer), then notify the backend fire-and-forget. The download store is not touched.
    val leaveEvent: LeaveEvent by lazy {
        LeaveEvent(
            config = services.config,
            stopUploads = { uploadTransitions.onLeave() },
            clearLedger = { services.ledger.clear() },
            scope = scope,
            notifyLeave = notifyLeave,
            pendingLeaves = membershipEnd.pendingLeaves,
        )
    }

    // The join-time load (capability `photo-sharing`), built in `shareSetLoadFor`. Public for
    // the world harness, whose operator provision runs this instance rather than a copy.
    val shareSetLoad: ShareSetLoad by lazy { shareSetLoadFor(services, backend.deviceFiles) }
    internal val receivedPhotoAdoption by lazy { receivedPhotoAdoptionFor(services, backend, ports.gallery, this) }

    /** The union one provision has read, handed from its adoption to its reconcile — see [JoinUnion]. */
    internal val joinUnion = JoinUnion()

    // The in-place reconfigure use-case (capability `manage-membership`): rewrite the joined
    // membership's participation fields (direction/cutoff/album) whole, then re-drive the provision-side
    // effects. Upload ARMS on enable but drains on disable (no stop); download reconciles on enable and
    // cancels in-flight on disable — the deliberate arm asymmetry lives in the tested use-case.
    val reconfigureEvent: ReconfigureEvent by lazy { reconfigureEventFor() }

    // The join use-case (capability `join-event`): fetch details, enroll by writing the register-only
    // EMPTY device manifest, then provision through the same path as create/scan.
    val joinEvent: JoinEvent by lazy {
        JoinEvent(
            configSource = services.config,
            identity = services.deviceIdentity,
            details = backend.directory,
            enroller = ManifestDeviceEnroller(backend.join),
            // Every provision route — interactive join, switch, retry, `autoJoin`, a create routed into the
            // join gate — passes here, so the album gather is started once the provision returns. It starts
            // HERE rather than inside `flow/Provision`: a flow may not detach work (law "A trigger flow never
            // outlives its own run"), and the gather must not hold up the join (capability `event-album`).
            // Built HERE, not supplied by the shell: the world used to bind provision to a body of its own, so a
            // join in the world never ran `flow/Provision`. Labelled `provisionEvent` so the flow's steps carry it.
            provision = { cfg ->
                joinUnion.during(cfg.eventId) {
                    services.log.invocation(process.entryContext, "provisionEvent") { provisionFlow.run(cfg) }
                }
                albumGather.start("provision", cfg.eventId)
            },
        )
    }

    // The membership-refresh rule (capability `join-event`): what a fetched details result MEANS for the
    // persisted membership — seated in `feature/membership` because that config is the feature's durable
    // state. The *fetch* it pairs with is [fetchEventDetails], coordinated by the Foreground flow — the
    // sole trigger that refreshes. It reads the clock because the absence verdict needs a second,
    // OFFLINE witness.
    val membershipRefresh: MembershipRefresh by lazy {
        MembershipRefresh(
            configSource = services.config,
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
    // will tear anything down (capability `manage-membership`).
    private val fetchEventDetails: suspend (eventId: String) -> JoinLoad = { eventId ->
        backend.directory.fetch(eventId).toJoinLoad()
    }

    /** The create-event status the use-case drives and the container reads (same instance). */
    val creationStatus = MutableStateFlow<CreationStatus>(CreationStatus.Idle)

    /** The rename status the use-case drives and the container reads (same instance, capability
     *  `manage-membership`) — the create twin, but carrying a success value the screen must clear. */
    val renameStatus = MutableStateFlow<RenameStatus>(RenameStatus.Idle)

    // The rename use-case (capability `manage-membership`): rewrite the shared event's name on the backend,
    // then fold the ECHOED name into this membership's config. The fifth writer of that config, seated
    // in `feature/membership` beside the reconfigure for exactly that reason.
    val renameEvent: RenameEvent by lazy {
        RenameEvent(
            configSource = services.config,
            client = backend.rename,
            status = renameStatus,
        )
    }

    // The create-event use-case: mint via the backend, then route the minted event into the SAME
    // join gate a scanned QR takes (capability `photo-sharing`).
    val eventCreator: EventCreator by lazy {
        CreateEvent(
            client = backend.creation,
            status = creationStatus,
            onMinted = onEventMinted,
        )
    }

    /**
     * Voids this device's durable sync state (`docs/testing.md`) so a build pointed at a
     * different backend starts from nothing. Emptying the ledger is enough to make the next cycle upload
     * everything in scope: every walk is a full enumeration, so there is no cursor to invalidate.
     *
     * Public because the dev/test control channel drives it directly. It has no user path by design —
     * `manage-membership` deliberately keeps the ledger, and this is the one operation for which that reasoning
     * stops holding — so there is no command-bundle entry to reach it through.
     */
    val resetDeviceState: ResetDeviceState by lazy {
        ResetDeviceState(
            config = services.config,
            ledger = services.ledger,
            // Read-only here now: the reset reports how many imported rows SURVIVED, which is the number
            // that makes "imported rows were kept" verifiable rather than assumed.
            downloads = services.downloadStore,
            // The download half is the CONTROLLER's, not the store's: the prune must run under the
            // controller's lock, because a ref is claimed under it and a reset that merely reads a
            // snapshot of what is claimed leaves a window for a claim in between — whose row is then
            // pruned, so its change block's marker write lands on nothing. Passing the critical section
            // rather than the value is also what keeps the membership feature blind to its sibling.
            resetDownloads = { downloadController.onDurableStateReset() },
        )
    }

    // ---- Selection-driven reads under a partial grant (capability `photo-access`) -----------
    // The latest selection snapshot (set only by the selection subscription below). The walk-vs-snapshot
    // decision is DERIVED per read from current permission + this cell, so it has exactly one owner and
    // no stored mode can go stale across a permission flip. `null` is "not read yet", which is NOT an empty
    // selection: it derives `SelectionScope.Unread`, and the app's upload admission withholds on it.
    private val latestSelectionSnapshot = MutableStateFlow<List<Resource>?>(null)

    // The gallery's selection snapshots, handed over by its `onChanged` handler. CONFLATED: each is the whole
    // selection, so an unconsumed older one is superseded — and one that arrives before the collector below runs
    // is kept for it, never dropped (the baseline a background-launched, later-foregrounded process used to lose).
    private val selectionChanges = Channel<SelectionSnapshot>(Channel.CONFLATED)

    /** What the gallery tells this core — registered by the host zone's `listen` (see [galleryHandlers]). */
    val galleryHandlers: GalleryHandlers = galleryHandlers(services.downloadStore, services.log, selectionChanges)

    /** What the operating system's wakes and transfer sessions tell this core — see [AppEvents]. */
    val events: AppEvents = AppEvents(this)

    /**
     * What upload discovery may read right now (consumed by the tier controllers' `uploadCore` ports).
     *
     * The two inputs are this composition's to hold — the permission port's current value and the
     * snapshot cell above — and the derivation over them is `model/`'s
     * [app.snapsync.model.selectionScope], which is where the rule about what a partial-grant member
     * may upload at all belongs. A call and not a value because the answer changes between cycles.
     */
    fun selectionScope(): SelectionScope =
        // Fully qualified, not imported: the member and the `model/` function share a name deliberately
        // (this IS that derivation, over inputs only the composition holds), and a bare call would read
        // as recursion to anyone who did not check the arity.
        app.snapsync.model.selectionScope(ports.photoAccess.permission.value, latestSelectionSnapshot.value)

    /**
     * The one derivation, for this composition's status readers (capability `photo-sharing`).
     *
     * Both `N` and the join preview must admit exactly what the upload cycle admits, so all three reach
     * the policy the same way — through `selectionPolicyFor`, with the same two port readers. The cycle
     * gets there via the membership's supplier; these two call it directly, because they already hold the
     * config and are already in a coroutine.
     */
    private suspend fun selectionPolicyForMembership(config: EventConfig): SelectionPolicy =
        selectionPolicyFor(
            config = config,
            suppressedAssetIds = { services.downloadStore.suppressedLocalIds() },
            albumExcludedAssetIds = ::albumExclusionsWhenReadable,
        )

    /**
     * The denylisted-album reader, **asked only when the library can be read** — the third
     * permission-aware seam in this file, and the same shape as [PermissionAwareCandidateSource] and
     * [PermissionAwareAssetPresence]: choosing behaviour by a port's state is composition.
     *
     * **Measured, not assumed** (simulator, iOS 26.4, 2026-08-28). A `PHAssetCollection` fetch under
     * `NOT_DETERMINED` issues a non-preflight TCC request and iOS **presents the photo-permission
     * dialog** — `tccd` logs one `AUTHREQ_PROMPTING` for the app. The A/B that pins it: a joined
     * `UploadOnly` membership at `NOT_DETERMINED` produced exactly one prompt, while `DownloadOnly` —
     * identical in every other respect, but resolving to `DenyAll` before either exclusion reader is
     * called — produced none.
     *
     * That matters because the consumers stopped gating on the grant (capability `sync-status`): the
     * policy must now be derived **before** the read seam can answer that it has nothing to say, and
     * `selectionRulesFor` reads its exclusion sets eagerly. Without this the status refresh would raise
     * an unrequested system dialog on every foreground of a joined device whose grant is undetermined —
     * a state a member reaches by resetting privacy settings, and one the app must never answer with a
     * prompt it did not ask for. `AlbumCoordinator` already gates its own writes this way.
     *
     * The empty set is the **honest** answer rather than a fallback: the denylist is a subtraction and
     * the policy admits on doubt, so "no denylisted assets" is what an unreadable album structure means
     * anyway — which is why `photo-access` records the denylist as inert under a partial grant.
     *
     * The grant gate itself lives in [denylistedAlbumMembers], shared with the upload cycle, and asks only
     * under a FULL grant: under `LIMITED` the album structure is unreadable, so the lookup could only ever
     * answer the empty set it now answers without the round-trip.
     */
    private suspend fun albumExclusionsWhenReadable(cutoff: CaptureCutoff): Set<AssetId> =
        // The app tier admits on doubt: a failed lookup must never drop a real photo from the total.
        denylistedAlbumMembers(
            albumManager, cutoff, ports.photoAccess.permission.value, AlbumLookupFailure.AdmitOnDoubt,
            services.log,
        )

    /**
     * The status-refresh **rule** (capability `sync-status`): cheap local reads before the library
     * enumeration, and no count at all without a membership. Seated in `feature/status` because it has
     * three callers — the `Foreground` and `Provision` flows and `ReconfigureEvent`, which is a feature
     * and cannot hold a flow's ordering — so it is a rule rather than one flow's order (law "Rules in
     * features, order in flows", which offers exactly this alternative).
     *
     * The two port touches it cannot make are built here: the config read, and the one policy
     * derivation.
     */
    internal val statusRefresh: StatusRefresh by lazy {
        StatusRefresh(
            ledgerCounts = ledgerCounts,
            gallery = gallery,
            // The sibling feature, reached through a lambda so `feature/status` stays blind to it.
            refreshDownloadLine = { downloadStatusSource.refresh() },
            configSource = services.config,
            policyFor = ::selectionPolicyForMembership,
            log = services.log,
        )
    }

    // ── The OS-callback trigger flows (`docs/architecture.md`, "Rules in features, order in
    // flows"; migration step 8). Each is built here — features referenced directly, port/platform
    // touches injected from [ports] — and the shell entry points delegate to them. ────────────────

    // The foreground-gated status-counts poll (capability `sync-status`): started by the Foreground flow,
    // stopped by the Background flow — the cadence is the feature's rule.
    //
    // It ticks the GROUP through `StatusRefresh`, not the ledger source directly. Handing it `ledgerCounts`
    // is what let the poll and the refresh disagree about what the cheap local reads are, leaving the
    // download line refreshed once per foreground entry and the screen able to hold a false "In sync".
    val statusCountsPoller: StatusCountsPoller by lazy {
        StatusCountsPoller(scope, refreshCheapLocalReads = { statusRefresh.refreshCheapLocalReads() })
    }

    val foregroundFlow: Foreground by lazy {
        Foreground(
            downloadController = downloadController,
            membershipRefresh = membershipRefresh,
            statusPoller = statusCountsPoller,
            reloadConfig = { services.config.reload() },
            // The upload side's own work at a foreground entry; its top-up and walk are the tail's.
            settleStored = storedUploadSettleFor(services, backend.deviceFiles)::settle,
            refreshStatus = { statusRefresh.run() },
            activeEventId = { services.config.config.value?.eventId },
            fetchEventDetails = fetchEventDetails,
            refreshAttestation = { attestation.refresh() },
        )
    }

    val backgroundFlow: Background by lazy {
        Background(statusPoller = statusCountsPoller)
    }

    val silentPushFlow: SilentPush by lazy {
        SilentPush(
            reloadConfig = { services.config.reload() },
            refreshAttestation = { attestation.refresh() },
            // The push's own work: the download arm, with its own active-event and direction guards.
            // The upload arm is not a receiver any more: its work is the tail's, which the push's wake joins only for
            // the active event — [pushTailGuard], asked by the push handler after this flow returns.
            downloadReceiver = downloadPushReceiver::onSilentPush,
        )
    }

    /**
     * Whether a push's wake joins the tail — the upload arm's active-event guard (capability `receiving-photos`).
     * The limited-grant read discipline is the tail's own (its walk runs only under a full grant).
     */
    val pushTailGuard: PushTailGuard by lazy { PushTailGuard(services.config, services.log) }

    /** The process's opportunistic tail and what reaches it without an OS handler — see [AppTail]. */
    val tail: AppTail by lazy {
        AppTail(
            scope = scope,
            services = services,
            appUploader = { appUploader },
            entryContext = process.entryContext,
            downloads = { downloadController },
            mayCreate = appMayCreate,
            cadenceFacts = { cadenceFactsOf(this) },
            refreshCounts = { ledgerCounts.refresh() },
            finish = { trigger -> membershipEnd.endOfWake(trigger) },
        )
    }

    val provisionFlow: Provision by lazy {
        Provision(
            // From the union the entry's adoption read in this same provision, when it did (capability
            // `receiving-photos`): one union read per join.
            reconcileDownloads = { eventId -> downloadController.reconcile(eventId, known = joinUnion.take(eventId)) },
            albumCoordinator = albumCoordinator,
            activeEventId = { services.config.config.value?.eventId },
            // The order is `MembershipEntry`'s rule; the backend leave is awaited here, unlike the leave command's.
            enterMembership = membershipEntry(notifyLeave)::enter,
            saveConfig = { cfg -> services.config.save(cfg) },
            refreshStatus = { statusRefresh.run() },
            // Usable access (`grantsPhotoAccess`): this gate feeds only ensureAlbum's granted
            // parameter, and album creation works under a LIMITED grant (measured — capability
            // `photo-access`).
            hasUsableAccess = { ports.photoAccess.permission.value.grantsPhotoAccess },
            registerPush = { pushRegistration.reRegister(services) },
        )
    }

    /**
     * The **composition lane** this graph's scope runs on, taken from the scope itself rather than
     * named, so the two can never disagree (`docs/architecture.md`, law "Dispatcher lanes are
     * fixed by the composition").
     *
     * Commands need it explicitly because the composition scope does NOT govern them: the
     * presentation container launches an `intent { }` on an unconfined dispatcher, so a command's
     * synchronous prefix runs on whichever thread fired it — the main thread, for a tap. A `suspend`
     * function that never actually suspends (synchronous PhotoKit XPC behind a `suspend` signature is
     * exactly that shape) then runs to completion there.
     *
     * **A `check`, never a default lane**: the decorators may not supply one, and a default degrades silently —
     * `withContext(EmptyCoroutineContext)` changes dispatcher not at all, so every awaited tap would run to
     * completion on the thread that fired it, the main thread, including `sendDiagnostics`' ~700 KB log read.
     *
     * Unreachable today: every composition supplies one — the iOS shell's dedicated composition lane, the
     * full-stack harness's `newSingleThreadContext`, `runBlocking`'s event loop under the world runners,
     * `runTest`'s scheduler. The failure this converts is therefore the NEXT composition's, caught at
     * assembly rather than as a main-thread stall nobody attributes (`docs/architecture.md`, "Absence is
     * never silent"; "Dispatcher lanes are fixed by the composition").
     */
    internal val coreLane: CoroutineContext =
        checkNotNull(scope.coroutineContext[ContinuationInterceptor]) {
            "the composition scope carries no dispatcher: user commands would run on whatever thread " +
                "fired them, which for an awaited tap is the main thread (law \"Dispatcher lanes are " +
                "fixed by the composition\" — the composition names the lane, and no default may)"
        }

    /** The user-tap command bundle — see [userCommandsFor]. */
    val userCommands: UserCommands by lazy { userCommandsFor() }

    /** The user-query bundle, lane-decorated beside the commands — see [userQueriesFor]. */
    val userQueries: UserQueries by lazy { userQueriesFor() }

    /**
     * The diagnostic dump assembly (capability `privacy-security`) — reads only, and only what this
     * graph already holds. Composed lazily like everything else here, so an unconfigured build (which
     * never fires the command) never builds it.
     */
    internal val collectDiagnosticDump: CollectDiagnosticDump by lazy {
        CollectDiagnosticDump(
            environment = ports.process.build.diagnostics,
            logs = services.deviceLogs,
            ledger = services.ledger,
            downloads = services.downloadStore,
            config = services.config,
            permission = galleryAccess,
            uploadFacts = {
                mapOf(
                    "extension_registrable" to extensionRegistrableNow().toString(),
                    "app_admission" to appUploadAdmission().name,
                )
            },
        )
    }

    /**
     * Install the **port-state-transition subscriptions** on the permission StateFlow (spec
     * `docs/architecture.md`, "Commands cross one door": installed in `compose/`; the transition
     * semantics — the upload permission-change transition, sole-creator album ensure — are feature rules),
     * and run the upload **launch reconcile** (capability `background-upload`, "Launch reconciles by
     * comparison; only a join forces the repair").
     *
     * The launch reconcile is explicit and the upload subscription skips the StateFlow's replayed value. It
     * used to ride that replay — every UI launch fired a "permission change" that forced the extension's
     * re-registration, wiping its in-flight jobs on every launch. Launch now compares instead.
     *
     * Deliberately an **explicit step, not `init`** (step 8 C3, restoring the pre-C2 timing): the app
     * shell invokes it from its host-assembly path — the only place the collectors ever installed — so
     * a cold background wake that merely touches [AppCore] runs **no** launch reconcile and installs no
     * collector. Call it once; each call installs a fresh set of collectors. The selection observer is not one of
     * them: it opens on every start ([installCompositionSubscriptions]).
     */
    fun installPermissionSubscriptions() {
        scope.launch {
            // Launch first, then real changes only: the value the launch reconciled against is not a
            // transition, and a change that lands during the launch reconcile is still delivered (the prefix
            // dropped is exactly the launch-time value). The transitions decide everything else.
            val atLaunch = ports.photoAccess.permission.value
            uploadTransitions.onLaunch()
            ports.photoAccess.permission
                .dropWhile { it == atLaunch }
                .collect { onGrantChanged(it) }
        }
        // The event album's grant subscription: ensure the album, then let the gather judge the emission.
        scope.launchAlbumGrantSubscription(services, albumCoordinator, albumGather)
        scope.launch {
            // THE ONE ADJUDICATION CALL SITE (capability `receiving-photos`). Once per process, here, and
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
            if (ports.photoAccess.permission.value == GalleryAccess.LIMITED) {
                latestSelectionSnapshot.filterNotNull().first()
            }
            downloadController.sweepInterruptedImports()
        }
    }

    /**
     * **The subscriptions every start installs, a background one included.** Start registering the device's APNs
     * token, and keep the registration alive across a credential
     * change (capability `receiving-photos`). Installed on **every cold start** — a foreground launch and a
     * background wake alike (a silent push, a background-`URLSession` relaunch, the upload heartbeat) — by the
     * shared host composition as it composes the graph (capability `sync-status`, "Push registration is started
     * by the shared composition"). Unlike [installPermissionSubscriptions], which a cold background wake must not
     * install, this one is needed there: a rotated APNs token or a renewed credential learned in a background wake
     * is published from that wake, never deferred to the next foreground. It is cheap there because the
     * registration publishes only on a changed (`token`, `env`, `deviceId`) triple, a join, or a fresh credential.
     *
     * **Idempotent: once per process.** Any second call — a host assembly after a background start, or any other
     * path — installs nothing, so a delivered token is published at most once.
     *
     * **ATTEST FIRST.** `PUT /devices/<id>` is gated, and on a fresh install the APNs token can arrive
     * before this device has attested at all — measured on the SE2, where that `PUT` took a `401`.
     * Awaiting a refresh first removes the race.
     *
     * **THE `tokenChanged` ARM IS THE POINT, and it is a JOIN BETWEEN TWO BLIND FEATURES** — trust emits
     * that a new credential exists, push consumes it. Neither knows the other, and the join is the whole
     * recovery path for a registration the backend refused: the device publishes a delivered token only when it
     * differs from the last registration the backend accepted, so without this a refused `PUT` waits for the next
     * app entry — and a device that receives no silent pushes gets few of them, and none of the wake-driven
     * attestation renewals that depend on them.
     *
     * That is why it lives HERE rather than in the shell. A join is behaviour, not wiring; assembled in
     * `:app:*` it is untested by law and invisible to the world harness, so nothing would observe it being
     * removed. Composed here, the same call the device makes is the one the harness makes.
     *
     * The registration is composed here over the push ports (see [pushRegistrationFor]); the token source is
     * the shell's, delivered by the OS.
     *
     * **And open the selection observer** and its collector (capability `photo-access`), for the same reason: under a
     * partial grant the observer's first emission is the start's baseline read and every later one a change the member
     * made — in the in-app picker, in Settings, or by iCloud sync; under any other grant it reads nothing. Without it a
     * background start under a partial grant never learns its selection, withholds every upload, and the app's own
     * wake-ups upload nothing (capability `background-upload`, "Photos upload without the app being opened"). Measured
     * on an SE2 / iOS 26.6.2: a background start reads the selection and raises no limited-library prompt (decision
     * record `changes/timely-background-receiving`, D6).
     */
    @OptIn(ExperimentalAtomicApi::class)
    fun installCompositionSubscriptions() {
        if (!compositionSubscriptionsInstalled.compareAndSet(expectedValue = false, newValue = true)) return
        scope.launch {
            runCatchingCancellable { attestation.ensureFresh() }
            pushRegistration.run(services.pushTokens, attestation.tokenChanged)
        }
        scope.launch {
            // One selection-change emission → ONE read serving both consumers (capability
            // `photo-access`, "One discovery serves both the status total and the enqueue"):
            // the cell feeds the cycle's discovery AND backs the permission-aware candidate source, so
            // `refresh` recounts N over the very same snapshot — no second library read on this path, and
            // no snapshot-specific entry point for the total to drift through.
            for (snapshot in selectionChanges) {
                latestSelectionSnapshot.value = resourcesFrom(snapshot.assets)
                services.config.config.value?.let { cfg -> gallery.refresh(selectionPolicyForMembership(cfg)) }
                // Its own work — the snapshot-fed discovery → manifest — then the tail (① and ② from the snapshot).
                tail.onSelectionChanged()
            }
        }
        // The selection observer opens here and nowhere else — on composition, so a background start reads it too.
        ports.gallery.observeChanges(true)
    }

    /** Whether [installCompositionSubscriptions] has installed its subscriptions in this process. */
    @OptIn(ExperimentalAtomicApi::class)
    private val compositionSubscriptionsInstalled = AtomicBoolean(false)

    /** The device's push registration (capability `receiving-photos`) — see [pushRegistrationFor]. */
    val pushRegistration: PushRegistration by lazy { pushRegistrationFor(services, backend.pushTokens) }

}

/**
 * The ONE app-graph composition (`docs/architecture.md`, "One shared composition"): the host zone calls this for every
 * root — the phone's, the JVM's, the rig's — so a wiring difference between binaries is impossible rather than
 * undetected. Manual DI (decision D6 of `establish-target-architecture`): plain constructors, no framework.
 *
 * Its FIRST act is the process's (`snapSyncProcess` over [AppPorts.process]): crash reporting starts before anything
 * else in the graph can fail, and no root can compose an app in a process that has not set it up — no root calls it.
 * [onEventMinted] is the host zone's join gate, where a minted event goes.
 */
fun snapSyncApp(
    scope: CoroutineScope,
    ports: AppPorts,
    onEventMinted: suspend (eventId: String) -> Unit,
): AppCore = AppCore(scope, snapSyncProcess(ports.process), ports, onEventMinted)
