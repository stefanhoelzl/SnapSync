package app.snapsync.compose

import app.snapsync.application.trust.RefreshAttestation
import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BuildTarget
import app.snapsync.data.backend.Observers
import app.snapsync.data.crypto.DownloadOpening
import app.snapsync.data.crypto.NewEventKeys
import app.snapsync.data.crypto.UploadSealing
import app.snapsync.data.service.ServiceConditions
import app.snapsync.data.trust.AttestationStatus
import app.snapsync.data.trust.AttestingCredential
import app.snapsync.data.version.AppVersionGate
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.entrypoints.OnSelectionChanged
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.album.AlbumGather
import app.snapsync.feature.creation.CreateEvent
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.feature.creation.readmodel.MintedEvent
import app.snapsync.feature.diagnostics.CollectDiagnosticDump
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.DownloadPushReceiver
import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.feature.links.readmodel.OpenedLinks
import app.snapsync.feature.membership.JoinUnion
import app.snapsync.feature.membership.ShareSetLoad
import app.snapsync.feature.push.PushRegistration
import app.snapsync.feature.status.LedgerBackedSyncStatusSource
import app.snapsync.feature.status.LedgerCounts
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.feature.status.ReadingLedgerCountsSource
import app.snapsync.feature.status.ShareableCountSource
import app.snapsync.feature.status.StatusCountsPoller
import app.snapsync.feature.status.StatusRefresh
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.feature.upload.PushTailGuard
import app.snapsync.feature.upload.UploadAdmission
import app.snapsync.feature.upload.UploadTransitions
import app.snapsync.feature.upload.appAdmission
import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.AlbumContents
import app.snapsync.model.EventConfig
import app.snapsync.model.EventCreator
import app.snapsync.model.Fact
import app.snapsync.model.ForegroundReturn
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionScope
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.model.extensionRegistrable
import app.snapsync.model.selectionPolicyFor
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.Databases
import app.snapsync.ports.DateFormatting
import app.snapsync.ports.DevControls
import app.snapsync.ports.DeviceConditions
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.Download
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.Gallery
import app.snapsync.ports.HttpTransport
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.Links
import app.snapsync.ports.NetworkMonitor
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.Port
import app.snapsync.ports.Preferences
import app.snapsync.ports.ProcessInfo
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SystemUi
import app.snapsync.ports.TransferSession
import app.snapsync.ports.Ui
import app.snapsync.ports.Wake
import app.snapsync.query.encryption.EventKeyQuery
import app.snapsync.query.membership.JoinDetailsQuery
import app.snapsync.query.membership.MembershipStatuses
import app.snapsync.scheduling.Observation
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.RuntimePorts
import app.snapsync.services.backend.BackendServices
import app.snapsync.services.crash.FootprintSampler
import app.snapsync.services.downloads.DownloadJobs
import app.snapsync.services.downloads.ImportMarkers
import app.snapsync.services.foreground.AppForeground
import app.snapsync.services.gallery.AlbumLookupFailure
import app.snapsync.services.gallery.CandidateSource
import app.snapsync.services.gallery.GalleryAccessState
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.gallery.GalleryAssetPresence
import app.snapsync.services.gallery.GalleryCandidateSource
import app.snapsync.services.gallery.GalleryDiscovery
import app.snapsync.services.gallery.GalleryImporter
import app.snapsync.services.gallery.ImportedAssetPresence
import app.snapsync.services.gallery.LatestSelection
import app.snapsync.services.gallery.PermissionAwareAssetPresence
import app.snapsync.services.gallery.PermissionAwareCandidateSource
import app.snapsync.services.gallery.denylistedAlbumMembers
import app.snapsync.services.links.LinkOpening
import app.snapsync.services.transfer.SessionDrains
import app.snapsync.services.upload.ExtensionRegistration
import app.snapsync.services.upload.OsDrivenRegistration
import app.snapsync.services.upload.Resuming
import app.snapsync.services.upload.SessionTransfer
import app.snapsync.services.upload.TransferSetup
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * **The app process's ports** — everything the app-graph composition consumes, and nothing but ports ([Port]; spec
 * `docs/architecture.md`, "One shared composition"). The root constructs its platform adapters and supplies them here;
 * [snapSyncHost][app.snapsync.compose] composes the process ([ProcessPorts] first — its crash reporting before
 * anything else), then the services over these ports ([AppServices]), then the feature graph.
 *
 * No defaults: a port added here is added once, and every root fails to compile until it answers — the phone's, the
 * JVM's and the rig's alike. Nothing else rides here: services are built by the composition itself, build constants
 * are read from [BuildInfo], and a main lane is owned by the adapter that needs it.
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
     * The platform's background downloads. An event port: the host zone registers
     * this core's [AppEvents.downloadHandlers] on it as the graph is composed, so a background relaunch that delivers
     * finished transfers finds them.
     */
    val download: Download,
    /**
     * HTTP, and nothing above it (`docs/architecture.md`, "The backend boundary") — production passes
     * `:adapter:generic:app`'s `KtorHttpTransport` over the platform's HTTP client. The backend's protocol is composed
     * over it here — ONE exchange, ONE gateway — so no root can wire a call without the credential or without the
     * version verdicts.
     */
    val http: HttpTransport,
    /** The platform's device-integrity service (App Attest on iOS) — what the attestation service proves with. */
    val integrity: DeviceIntegrity,
    /**
     * The app's own uploader session (a background `URLSession` on iOS, Android's uploads) — what the app uploader's
     * cycle starts its transfers on, and an event port: the host zone registers this core's [AppEvents.uploadHandlers]
     * on it as the graph is composed, so a relaunch that hands back finished uploads records them.
     */
    val uploadSession: TransferSession,
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
    /** The platform's push service: asked for the token at launch and at every
     *  foreground entry; the host zone registers this core's [pushHandlers] on it as the graph is composed. */
    val pushNotifications: PushNotifications,
    /** The app's foreground life — an event port the host zone registers the foreground work on. */
    val lifecycle: Lifecycle,
    /** The links the platform opens the app with — an event port the host zone registers the link handler on. */
    val links: Links,
    /** The platform's user interface — shown every state the status host reduces, from host assembly on. */
    val ui: Ui<UiState, UiIntent>,
    /** What the OS says about this process — whether protected storage is readable right now, recorded by the
     *  background entry points and deciding nothing. */
    val processInfo: ProcessInfo,
    /** The device's network as the operating system reports it to this app — watched only while the app is in front,
     *  to tell the member when it has none. */
    val network: NetworkMonitor,
    /** The device's power, battery, thermal state and background allowance — read only for a bug report. */
    val deviceConditions: DeviceConditions,
    /** The platform's date formatting — how a date reads on the screen; the core formats no date
     *  (`docs/architecture.md`, "Localization"). The screen renders through it, and only the app has one. */
    val dateFormatting: DateFormatting,
)

/**
 * The composed app graph. Every property is `by lazy`: nothing here resolves the device identity or touches a
 * platform store until its first use — the property a locked background launch depends on.
 */
class AppCore internal constructor(
    /**
     * The process's runtime (`docs/patterns.md`, "Entry points"): every entry point this graph builds runs through it,
     * and its scope is the one the old zones' own launches still use until phase 4 moves them.
     */
    val runtime: Runtime,
    /**
     * The **composition lane** [scope] runs on (`docs/architecture.md`, law "Dispatcher lanes are fixed by the
     * composition") — named by the root, never derived, so no composition can be built without one.
     *
     * Commands need it explicitly because the composition scope does NOT govern them: the presentation container
     * launches an `intent { }` on an unconfined dispatcher, so a command's synchronous prefix runs on whichever thread
     * fired it — the main thread, for a tap. A `suspend` function that never actually suspends (synchronous PhotoKit
     * XPC behind a `suspend` signature is exactly that shape) then runs to completion there.
     */
    internal val coreLane: CoroutineDispatcher,
    /** What the process set up before this core existed — its crash reporting already started. */
    val process: ProcessServices,
    internal val ports: AppPorts,
) {
    /** The process scope — the runtime's. */
    internal val scope: CoroutineScope get() = runtime.scope

    /** The state whose changes start work, each handed to its entry point. */
    internal val observation: Observation = Observation(runtime)

    /** This process's services over [ports] — built here, never by a root (see [AppServices]). */
    internal val services: AppServices = AppServices(ports, process)

    /**
     * The app's uploader — the app-driven tier on every OS version, over the app's own [AppPorts.uploadSession]. Always
     * the real one: a composition whose transport is mocked is driven at that transport.
     */
    internal val appUploader: AppUploader by lazy { AppUploader(this) }

    /**
     * Whether the backend is refusing this build as too old. NOT lazy: the
     * status host observes it from its first frame, and a lazy cell created by a late reader could miss nothing
     * today but would invite a writer that captured an earlier one.
     */
    val versionGate: AppVersionGate = AppVersionGate()

    /**
     * What the app's credential has learnt — whether this device holds a usable token, why the service refused it, the
     * refusal's facts, and every new token. NOT lazy, for [versionGate]'s reason: the status host observes it from its
     * first frame.
     */
    val attestationStatus: AttestationStatus = AttestationStatus()

    /**
     * The app's credential — the bearer token EVERY backend call carries, kept alive and recovered when the backend
     * rejects it. Composed here; only the app process can attest (`DCAppAttestService.isSupported` is false in an app
     * extension — measured), and the extension reads the token this writes.
     */
    internal val attestation: AttestingCredential by lazy {
        attestingCredentialFor(services, process.clock, exchange, attestationStatus)
    }

    /** The wake's refresh of [attestation], the use case every wake's prelude calls. */
    internal val refreshAttestation: RefreshAttestation by lazy { RefreshAttestation(attestation) }

    /**
     * The backend's protocol over [AppPorts.http] (`docs/architecture.md`, "The backend boundary"): where every request
     * goes, the version it declares, and the reply it reads — reporting a refused build to [versionGate]. The
     * credential sends the token issuers through it; every other request goes through [backend]'s gateway.
     */
    val exchange: BackendExchange by lazy {
        BackendExchange(ports.http, BuildTarget(process.build), Observers(versionGate, serviceConditions))
    }

    /**
     * Whether SnapSync's service is failing, as the latest answer told — the joined screen's service line. NOT lazy,
     * for [versionGate]'s reason; an opening forgets it ([foreground]).
     */
    val serviceConditions: ServiceConditions = ServiceConditions()

    /** [serviceConditions]' cell, for readers outside the core (the status host), which see no service type. */
    val serviceFault: StateFlow<ServiceFault?> get() = serviceConditions.fault

    /**
     * The app's foreground life: whether it is in front now, and its returns as the create screen's draft follows
     * them — stamped by the lifecycle's entry points over the process clock.
     */
    internal val foreground: AppForeground = AppForeground(process.clock, onEntered = { serviceConditions.opening() })

    /** [foreground]'s returns, for readers outside the core (the status host), which see no service type. */
    val foregroundReturns: StateFlow<ForegroundReturn> get() = foreground.returns

    /** The app's own memory readings, taken while it settles in the background. */
    internal val footprints: FootprintSampler by lazy {
        FootprintSampler(
            ports.processInfo,
            process.footprints,
            foreground,
        )
    }

    /** [versionGate]'s cell, for readers outside the core (the status host), which see no service type. */
    val versionRefusal: StateFlow<VersionRefusal?> get() = versionGate.refusal

    /** [attestationStatus]' health cell, for readers outside the core (the status host), which see no service type. */
    val attested: StateFlow<Boolean> get() = attestationStatus.attested

    /** Why the service refused this phone at the latest attempt to verify it. */
    val refusal: StateFlow<DeviceRefusal?> get() = attestationStatus.refusal

    /** How SnapSync's service failed the latest attempt to verify this phone, if it did. */
    val attestationFault: StateFlow<ServiceFault?> get() = attestationStatus.fault

    /**
     * Every need-shaped backend service, over one gateway whose credential is [attestation] (a rejected
     * token is dropped, a new one obtained, and the request retried once) and whose
     * verdicts reach [versionGate] through [exchange]. Public for the app root's uploader, which publishes its manifest
     * through [BackendServices.manifest] like every other caller.
     */
    val backend: BackendServices by lazy {
        backendServicesFor(services, attestation, exchange, eventOver = { services.config.markOver(it) })
    }

    /**
     * What makes the app's file uploads resume where a failed attempt stopped (`docs/architecture.md`, "Resumable
     * uploads") — shared by the cycle that starts them and the transfer that records their end, which must agree on
     * keeping a failed upload's staged file.
     */
    internal val uploadResuming: Resuming by lazy { uploadResumingFor(services, backend, process.crypto) }

    // Own-device completeness AND in-flight, both from one consistent per-photo `assetProgress()` read,
    // counted by the status source over the gallery's admitted set. Read-only;
    // on any failure the last good value is retained.
    val ledgerCounts: ReadingLedgerCountsSource by lazy {
        ReadingLedgerCountsSource { LedgerCounts.of(services.ledger.assetProgress()) }
    }

    /**
     * The one read seam the app's consumers hold — the grant decides the backing, not the consumer.
     * Built here because choosing between ports by a third port's
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
            selection = selection.snapshot,
        )
    }

    /**
     * The one presence source the download guard holds: full access queries the library, a partial grant
     * answers from the snapshot and never says "absent", no grant answers unknown. Built here for the
     * same reason as [candidates] — choosing a source by the grant is composition, and both halves are
     * available here.
     */
    private val assetPresence: ImportedAssetPresence by lazy {
        PermissionAwareAssetPresence(
            permission = galleryAccess.grant,
            library = GalleryAssetPresence(ports.gallery),
            selection = selection.snapshot,
        )
    }

    // The own-device upload TOTAL N: gallery enumeration minus downloaded foreign photos, scoped by the
    // membership's cutoff and the origin exclusions — the SAME policy the upload cycle gets, because the
    // two enumerate independently and a rule applied to one and not the other would peg the joined
    // screen below 100% forever.
    val gallery: OwnDeviceGalleryStatusSource by lazy { OwnDeviceGalleryStatusSource(candidates) }

    // The join-time shareable-count preview: the SAME policy the cycle and
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
     * The join surface's live "how many photos from your gallery will be shared" query: for a
     * candidate [cutoff] with sharing on, the count of own photos the policy admits, or `null` when
     * the grant permits no count. Purely local — no backend LIST.
     */
    suspend fun loadShareableCount(cutoff: CaptureCutoff, until: CaptureCeiling?): Int? =
        shareableCountSource.count(includesUpload = true, cutoff = cutoff, ceiling = until)

    /** What the photo-library grant means now, over the permission port — the one read every feature takes. */
    val galleryAccess: GalleryAccessState by lazy { GalleryAccessState(ports.gallery.grant) }

    /** The membership's cell, for readers outside the core (the status host), which see no service type. */
    val membership: StateFlow<EventConfig?> get() = services.config.config

    /** What the screen reads of the joined event's key: whether it is held, and so the key its invite carries. */
    val keyQuery: EventKeyQuery by lazy { EventKeyQuery(services.membership, services.eventKeyStore, scope) }

    /** The device's mobile-data choice, for the status host's menu and waiting line. */
    val mobileDataChoice: StateFlow<Boolean> get() = services.mobileData.allowed

    /** What the event album holds on this phone, for the status host's album wording. */
    val albumContents: AlbumContents get() = albumManager.contents

    /** The photo-access grant, exposed for the join surface's count-recompute trigger (a late resolve). */
    val photoPermission: StateFlow<GalleryAccess> get() = galleryAccess.grant

    /** The real ledger-backed status source (ledger truth × permission × gallery total). */
    val syncStatusSource: SyncStatusSource by lazy {
        LedgerBackedSyncStatusSource(ledgerCounts, galleryAccess, gallery.admitted, scope)
    }

    // Download progress for the joined screen's received count and download arrow, scoped to the joined event.
    val downloadStatusSource: StoreDownloadStatusSource by lazy { downloadStatus() }

    // Background byte transfers → durable staging. The queue, bounded window, and cancellation
    // lifecycle live in the tested feature; the transport is the shell's adapter thunk.
    val downloadJobs: DownloadJobs by lazy {
        // Staging is the port that also releases those bytes, so the two never name different directories;
        // the jobs record relative paths.
        DownloadJobs(
            staging = services.stagedBytes,
            download = ports.download,
            // Deliver each staged resource back to the controller — a compose-built lambda whose body is one call (law
            // "Commands cross one door"). It reads the `downloadController` lazy when INVOKED, not here: the jobs are
            // built on paths that build nothing else — a background-`URLSession` relaunch touches only `downloadJobs` —
            // and the callback must reach the controller on those paths too — a staged resource reaches the
            // controller on every entry point. No launch here: the jobs own it, so they can join the stagings before
            // the session's OS handler is released.
            // Staging is recorded here and the import is the tail's: requested detached — a staging holds no OS
            // handler of its own, and a wake that delivered it requests (and holds) its own tail after its drain.
            onStaged = { ref, key, path ->
                val recorded = downloadController.onResourceStaged(ref, key, path)
                if (recorded) work.downloadStaged.requested()
            },
            network = services.mobileData::transferNetwork,
            // The version every request declares — the download redirect's too (`downloadResource`).
            headers = exchange::versionHeaders,
            // An encrypted event's bytes are opened before they are staged (the encrypted file format).
            opening = DownloadOpening(services.membership, services.eventKeyStore, services.fileCipher, process.files),
        )
    }

    // The download orchestrator: union → foreign selection → download → import → suppression.
    val downloadController: DownloadController by lazy {
        DownloadController(
            runtime = runtime,
            union = backend.union,
            store = services.downloadStore,
            jobs = downloadJobs,
            importer = GalleryImporter(ports.gallery, services.stagedBytes),
            presence = assetPresence,
            // The import-time album: the membership's opt-in gate is the coordinator's rule; this only reads the
            // current membership's facts.
            eventAlbum = { albumCoordinator.albumIdFor(services.config.config.value) },
            onImportedIntoAlbum = { album -> albumCoordinator.onImportedInto(services.config.config.value, album) },
            stagedBytes = services.stagedBytes,
            identity = services.deviceIdentity,
            arm = downloadArm(),
            checks = services.eventChecks,
            clock = services.wallClock,
            readyToImport = { importsReady() },
        )
    }

    // The silent-push receiver for the download arm; its active-event
    // guard is the feature's rule.
    val downloadPushReceiver: DownloadPushReceiver by lazy {
        DownloadPushReceiver(
            configSource = services.config,
            controller = downloadController,
        )
    }

    /** The album operations over the gallery. */
    private val albumManager: GalleryAlbums by lazy { GalleryAlbums(ports.gallery) }

    // Event album: the coordinator over the shared leave-surviving map.
    // The APP is the SOLE creator (on the permission grant); both processes only add.
    val albumCoordinator: AlbumCoordinator by lazy {
        AlbumCoordinator(albumManager, services.albumMapStore)
    }

    // The event album's gather: place what the device already holds for the event.
    // Built HERE and nowhere in the extension's graph, so "the extension never gathers" holds by
    // construction. Started, never awaited, by the act that triggered it.
    val albumGather: AlbumGather by lazy {
        albumGather(
            services,
            galleryAccess,
            albumCoordinator,
            scope,
            ::selectionPolicyForMembership,
        )
    }

    /**
     * Whether the upload extension may be registered now — one pure fact — read fresh wherever it is needed, never
     * held: it is a function of the runtime grant, so a captured answer would be stale exactly when it mattered.
     */
    val extensionRegistrableNow: () -> Boolean = {
        extensionRegistrable(
            osSupportsOsDrivenUpload = ports.extensionRegistry.supported,
            permission = galleryAccess.grant.value,
            pin = ports.devControls.uploaderPin(),
        )
    }

    /**
     * Whether the app's uploader may create now — the app process's admission, which its entry gate consumes:
     * the upload cycle owns its entry decision.
     */
    val appUploadAdmission: () -> UploadAdmission = {
        // The scope, not the raw cell: an unread partial-grant selection withholds (`appAdmission`), and it is
        // the same derivation discovery reads through `selectionScope()`.
        appAdmission(galleryAccess.grant.value, selectionScope(), ports.devControls.uploaderPin())
    }

    /** [appUploadAdmission] as a Boolean — what the app pump's completion re-pump reads; the delegate records the
     *  terminal fact before it returns. */
    val appMayCreate: () -> Boolean = { appUploadAdmission().admits }

    // The upload arm: what each membership transition does to the two
    // uploaders. Stateless — every decision is derived from the registration fact, the grant and whether a
    // membership exists, at the moment of the transition; the root defaults nothing.
    /** The OS-driven mechanism's registration ritual, over the registry port. */
    val extensionRegistration: ExtensionRegistration by lazy {
        OsDrivenRegistration(ports.extensionRegistry, services.log)
    }

    internal val linkOpening: LinkOpening by lazy { LinkOpening(process.logger("links")) }

    /** The link the app was last opened with, until the screen has opened it. */
    val openedLinks: OpenedLinks = OpenedLinks()

    val uploadTransitions: UploadTransitions by lazy {
        UploadTransitions(
            configSource = services.config,
            photoAccess = galleryAccess,
            extensionRegistrable = extensionRegistrableNow,
            registration = extensionRegistration,
            appEngine = { work.uploadEngine },
            log = services.log,
        )
    }

    // The join-time load, built in `shareSetLoadFor`. Public for
    // the world harness, whose operator provision runs this instance rather than a copy.
    val shareSetLoad: ShareSetLoad by lazy { shareSetLoadFor(services, backend.deviceFiles) }
    internal val receivedPhotoAdoption by lazy { receivedPhotoAdoptionFor(services, backend, ports.gallery, this) }

    /** The union one join has read, handed from its adoption to its reconcile — see [JoinUnion]. */
    internal val joinUnion = JoinUnion()

    /** The member's requests of the membership, each answered on its own status. */
    val membershipStatuses: MembershipStatuses = MembershipStatuses()

    // Every event this device mints, emitted once the backend answered: the host zone collects it into its join gate —
    // the SAME gate a scanned QR takes — from its composition on, before any tap can
    // create one, and nothing else does.
    private val minted = MutableSharedFlow<MintedEvent>()

    /** Every event this device mints, for the host zone's join gate. */
    val mintedEvents: SharedFlow<MintedEvent> get() = minted

    /** The create-event status the use-case drives and the container reads (same instance). */
    val creationStatus = MutableStateFlow<CreationStatus>(CreationStatus.Idle)

    // The create-event use-case: mint via the backend, then route the minted event into the SAME
    // join gate a scanned QR takes.
    val eventCreator: EventCreator by lazy {
        CreateEvent(
            client = backend.creation,
            status = creationStatus,
            onMinted = { eventId, key -> minted.emit(MintedEvent(eventId, key)) },
            keys = NewEventKeys(services.eventKeyIds, ports.devControls),
        )
    }

    // ---- Selection-driven reads under a partial grant -----------
    /** The latest selection snapshot, set only by the selection subscription below — see [LatestSelection]. */
    private val selection = LatestSelection()

    /** The transfer sessions' drain reports, which a relaunch for their events waits for. */
    internal val uploadDrains: SessionDrains = SessionDrains()
    internal val downloadDrains: SessionDrains = SessionDrains()

    /** The download store's import markers — what the gallery's import deliveries write. */
    internal val importMarkers: ImportMarkers by lazy { ImportMarkers(services.downloadStore, services.log) }

    /** The app's own transfer session's service: what its deliveries record, and what a leave cancels. */
    val uploadTransfer: SessionTransfer by lazy {
        SessionTransfer(
            ports.uploadSession,
            TransferSetup(
                record = services.ledger,
                resources = GalleryDiscovery(ports.gallery),
                gallery = ports.gallery,
                files = process.files,
                network = services.mobileData::transferNetwork,
                sealing = UploadSealing(
                    services.membership,
                    services.eventKeyStore,
                    services.deviceIdentity,
                    services.fileCipher,
                ),
                log = services.log,
            ),
            uploadResuming,
        )
    }

    /**
     * What upload discovery may read right now (consumed by the tier controllers' `uploadCore` ports): the permission
     * port's current value over the snapshot cell — see [LatestSelection.scopeUnder]. A call and not a value because
     * the answer changes between cycles.
     */
    fun selectionScope(): SelectionScope = selection.scopeUnder(galleryAccess.grant.value)

    /**
     * The one derivation, for this composition's status readers.
     *
     * Both `N` and the join preview must admit exactly what the upload cycle admits, so all three reach
     * the policy the same way — through `selectionPolicyFor`, with the same two port readers. The cycle
     * gets there via the membership's supplier; these two call it directly, because they already hold the
     * config and are already in a coroutine.
     */
    private suspend fun selectionPolicyForMembership(config: EventConfig): SelectionPolicy =
        selectionPolicyFor(
            config = config,
            now = services.config.now(),
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
     * That matters because the consumers stopped gating on the grant: the
     * policy must now be derived **before** the read seam can answer that it has nothing to say, and
     * `selectionRulesFor` reads its exclusion sets eagerly. Without this the status refresh would raise
     * an unrequested system dialog on every foreground of a joined device whose grant is undetermined —
     * a state a member reaches by resetting privacy settings, and one the app must never answer with a
     * prompt it did not ask for. `AlbumCoordinator` already gates its own writes this way.
     *
     * The empty set is the **honest** answer rather than a fallback: the denylist is a subtraction and
     * the policy admits on doubt, so "no denylisted assets" is what an unreadable album structure means
     * anyway — which is why the denylist is recorded as inert under a partial grant.
     *
     * The grant gate itself lives in [denylistedAlbumMembers], shared with the upload cycle, and asks only
     * under a FULL grant: under `LIMITED` the album structure is unreadable, so the lookup could only ever
     * answer the empty set it now answers without the round-trip.
     */
    internal suspend fun albumExclusionsWhenReadable(cutoff: CaptureCutoff): Set<AssetId> =
        // The app tier admits on doubt: a failed lookup must never drop a real photo from the total.
        denylistedAlbumMembers(
            albumManager,
            cutoff,
            galleryAccess.grant.value,
            AlbumLookupFailure.AdmitOnDoubt,
            services.log,
        )

    /**
     * The status-refresh **rule**: cheap local reads before the library
     * enumeration, and no count at all without a membership. Seated in `feature/status` while the status is an old
     * zone's: its callers are the foreground's work, a join's (`AfterJoin`) and a settings change's (`AfterSettings`),
     * and it moves into the layers with the status (4.7).
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
            recognition = services.downloadStore.recognition,
            log = services.log,
        )
    }

    // The foreground-gated status-counts poll: started by the foreground's own work, stopped as the app leaves it —
    // the cadence is the feature's rule.
    //
    // It ticks the GROUP through `StatusRefresh`, not the ledger source directly. Handing it `ledgerCounts`
    // is what let the poll and the refresh disagree about what the cheap local reads are, leaving the
    // download line refreshed once per foreground entry and the screen able to hold a false "In sync".
    val statusCountsPoller: StatusCountsPoller by lazy {
        StatusCountsPoller(scope, refreshCheapLocalReads = { statusRefresh.refreshCheapLocalReads() })
    }

    /** The foreground-gated network watch and what it tells the member. */
    val network: AppNetwork by lazy { AppNetwork(scope, ports.network, statusCountsPoller) }

    /**
     * Whether a push's wake joins the wake's work — the upload arm's active-event guard.
     * The limited-grant read discipline is the uploads' own (the walk runs only under a full grant).
     */
    val pushTailGuard: PushTailGuard by lazy { PushTailGuard(services.config, services.log) }

    /** What the entry points run (`AppWork`): built once, on first touch, and handed to the ports. */
    internal val work: AppWork by lazy { AppWork(this) }

    /** The user-tap command bundle — see [userCommandsFor]. */
    val userCommands: UserCommands by lazy { userCommandsFor() }

    /** The user-query bundle, lane-decorated beside the commands — see [userQueriesFor]. */
    val userQueries: UserQueries by lazy { userQueriesFor() }

    /** The join gate's read of an invite's event, lane-decorated like the queries — see [joinDetailsFor]. */
    val joinDetails: JoinDetailsQuery by lazy { joinDetailsFor() }

    /** The diagnostic dump assembly — see [diagnosticDumpFor]. */
    internal val collectDiagnosticDump: CollectDiagnosticDump by lazy { diagnosticDumpFor() }

    /** How many photos a partial grant's selection holds, for a bug report — the snapshot cell is this core's. */
    internal fun selectionPhotosNow(): Fact<Int> =
        selection.photosUnder(galleryAccess.grant.value)

    /**
     * Install the **port-state-transition subscriptions** on the permission StateFlow (spec
     * `docs/architecture.md`, "Commands cross one door": installed in `compose/`; the transition
     * semantics — the upload permission-change transition, sole-creator album ensure — are feature rules),
     * and run the upload **launch reconcile**: launch reconciles by comparison; only a join forces the
     * repair.
     *
     * The launch reconcile is explicit and the upload subscription skips the StateFlow's replayed value: riding
     * that replay would fire a "permission change" on every UI launch, forcing the extension's re-registration and
     * wiping its in-flight jobs.
     *
     * Deliberately an **explicit step, not `init`**: the app
     * shell invokes it from its host-assembly path — the only place the collectors ever installed — so
     * a cold background wake that merely touches [AppCore] runs **no** launch reconcile and installs no
     * collector. Call it once; each call installs a fresh set of collectors. The selection observer is not one of
     * them: it opens on every start ([installCompositionSubscriptions]).
     */
    fun installPermissionSubscriptions() {
        // The launch reconcile, then real changes only — see `UploadTransitions.followGrant`.
        scope.launch { uploadTransitions.followGrant { onGrantChanged(it) } }
        // The event album's grant subscription: ensure the album, then let the gather judge the emission.
        scope.launchAlbumGrantSubscription(services, albumGather)
        scope.launch {
            // THE ONE ADJUDICATION CALL SITE. Once per process, here, and
            // nowhere else — not in `reconcile`, not in `importReady`, not in `onResourceStaged`. Only a
            // process that DIED can leave a row no running import will settle, so a running process asking
            // again about rows it opened itself buys nothing and costs a synchronous XPC round-trip each
            // time: 1,149 discarded verdicts in one measured burst.
            //
            // Ordered INSIDE this method, after the subscriptions above, rather than left to the shell to
            // sequence: the requirement is that the presence source can answer when the sweep asks, and a
            // convention the shell has to honour is not a guarantee.
            //
            // With one sweep per process and no re-arm, a sweep that ran before a partial grant's selection was read
            // would defer every inherited row to the next launch — and on a URLSession-driven relaunch that never
            // foregrounds, potentially every launch. So it waits until the presence source can answer.
            selection.awaitAnswerable(galleryAccess.grant.value)
            downloadController.sweepInterruptedImports()
        }
    }

    /**
     * **The subscriptions every start installs, a background one included.** Start registering the device's APNs
     * token, and keep the registration alive across a credential change. Installed on **every cold start** — a
     * foreground launch and a background wake alike (a silent push, a background-`URLSession` relaunch, the upload
     * heartbeat) — by the shared host composition as it composes the graph. Unlike [installPermissionSubscriptions],
     * which a cold background wake must not install, this one is needed there: a rotated APNs token or a renewed
     * credential learned in a background wake

     * is published from that wake, never deferred to the next foreground. It is cheap there because the
     * registration publishes only on a changed (`token`, `env`, `deviceId`) triple, a join, or a fresh credential.
     *
     * **Once per process, by construction:** its one caller is the host zone's composition, which runs once per
     * process — so a delivered token is published at most once.
     *
     * **ATTEST FIRST.** `PUT /devices/<id>` is gated, and on a fresh install the APNs token can arrive
     * before this device has attested at all — measured on the SE2, where that `PUT` took a `401`. The publisher
     * sends it through the gateway's `sendObtainingFirst`, which obtains a token before a PUT that holds none.
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
     * **And open the selection observer** and its collector, for the same reason: under a
     * partial grant the observer's first emission is the start's baseline read and every later one a change the member
     * made — in the in-app picker, in Settings, or by iCloud sync; under any other grant it reads nothing. Without it a
     * background start under a partial grant never learns its selection, withholds every upload, and the app's own
     * wake-ups upload nothing, where photos must upload without the app being opened. Measured on an SE2 / iOS 26.6.2:
     * a background start reads the selection and raises no limited-library prompt (decision record
     * `changes/timely-background-receiving`, D6).
     */
    @OptIn(CompositionOnly::class)
    fun installCompositionSubscriptions() {
        scope.launch { pushRegistration.run(services.pushTokens, attestationStatus.tokenChanged) }
        // One selection-change emission → ONE read serving both consumers, the status total and the enqueue:
        // the cell feeds the cycle's discovery AND backs the permission-aware candidate source, so
        // `refresh` recounts N over the very same snapshot — no second library read on this path, and
        // no snapshot-specific entry point for the total to drift through.
        // A selection bringing marked photos no pass recognised withholds the membership BEFORE any reader sees
        // it, and recognises them first (`changes/archive/2026-10-11-close-share-back-gaps`).
        observation.follow(
            selection.published(
                // The gallery keeps its latest snapshot, so one read before this collector runs is kept, not lost.
                ports.gallery.selection,
                beforePublish = { receivedPhotoAdoption.beforeSelectionPublished(services.config.config.value, it) },
            ),
            OnSelectionChanged(
                runtime,
                receivedPhotoAdoption,
                services.config,
                statusRefresh,
                work.ownUploads,
                work.wakeWork,
            ),
        )
        // The selection observer opens here and nowhere else — on composition, so a background start reads it too.
        ports.gallery.observeChanges(true)
    }

    /** The device's push registration — see [pushRegistrationFor]. */

    val pushRegistration: PushRegistration by lazy { pushRegistrationFor(services, backend.pushTokens) }
}

/**
 * The ONE app-graph composition (`docs/architecture.md`, "One shared composition"): the host zone calls this for every
 * root — the phone's, the JVM's, the rig's — so a wiring difference between binaries is impossible rather than
 * undetected. Manual DI (decision D6 of `establish-target-architecture`): plain constructors, no framework.
 *
 * Its FIRST act is the process's (`snapSyncProcess` over [AppPorts.process]): crash reporting starts before anything
 * else in the graph can fail, and no root can compose an app in a process that has not set it up — no root calls it.
 * [lane] is the composition lane [scope] runs on, named by the root ([AppCore.coreLane]).
 */
fun snapSyncApp(scope: CoroutineScope, lane: CoroutineDispatcher, ports: AppPorts): AppCore {
    val runtime = Runtime(
        scope.coroutineContext[Job],
        lane,
        RuntimePorts(ports.backgroundTime, ports.process.threadLabel, ports.process.entryEvents),
    )
    return AppCore(runtime, lane, snapSyncProcess(ports.process, runtime), ports)
}
