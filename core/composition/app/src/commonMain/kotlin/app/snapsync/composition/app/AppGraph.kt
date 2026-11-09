package app.snapsync.composition.app

import app.snapsync.application.crash.DescribeReporter
import app.snapsync.application.event.CreateEvent
import app.snapsync.application.membership.LoadShareSet
import app.snapsync.application.process.RecordFootprint
import app.snapsync.application.push.RegisterPush
import app.snapsync.composition.process.ProcessGraph
import app.snapsync.data.crash.ReportedIdsRead
import app.snapsync.data.crypto.NewEventKeys
import app.snapsync.data.event.EventCreation
import app.snapsync.data.foreground.AppForeground
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.gallery.LibrarySelection
import app.snapsync.data.gallery.PhotoGrants
import app.snapsync.data.links.LinkReading
import app.snapsync.data.links.OpenedInvites
import app.snapsync.data.process.FootprintReading
import app.snapsync.data.process.StartedProcess
import app.snapsync.data.sharing.DenylistedAlbums
import app.snapsync.data.sharing.GalleryWalk
import app.snapsync.data.sharing.MemoizedWalk
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.upload.DeviceListing
import app.snapsync.data.upload.OsUploader
import app.snapsync.data.upload.UploaderSwitches
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.foreground.ForegroundReturn
import app.snapsync.domain.foreground.InFront
import app.snapsync.domain.report.ReportOutcome
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.entrypoints.OnCredentialChanged
import app.snapsync.entrypoints.OnMembershipChanged
import app.snapsync.entrypoints.OnPushToken
import app.snapsync.entrypoints.OnPushTokenFailure
import app.snapsync.entrypoints.OnSelectionChanged
import app.snapsync.entrypoints.OnSilentPush
import app.snapsync.entrypoints.StartSubscriptions
import app.snapsync.entrypoints.UploaderPinMoved
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.PushHandlers
import app.snapsync.query.RequestStatuses
import app.snapsync.query.encryption.EventKeyQuery
import app.snapsync.query.links.OpenedInvitesQuery
import app.snapsync.query.membership.JoinDetailsLookup
import app.snapsync.query.membership.MembershipShown
import app.snapsync.query.membership.MembershipStatuses
import app.snapsync.query.push.PushTailLookup
import app.snapsync.query.receiving.DownloadProgress
import app.snapsync.query.sharing.PhotoGrantQuery
import app.snapsync.query.sharing.ShareableCountLookup
import app.snapsync.scheduling.Observation
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.SessionDrains
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/**
 * **The app process's graph** (`docs/architecture.md`, "The composition layer") over its [ports] and its started
 * [process]: built eagerly by [composeApp], and inert as it is built — every store opens on first use, and nothing
 * reads a port, a repository's state or the clock until a trigger runs (`ConstructorsTouchNoPort`). Wiring only: which
 * instance goes where.
 */
@OptIn(AppProcessOnly::class, CompositionOnly::class)
class AppGraph(
    val ports: AppPorts,
    /** The process's runtime: every entry point this graph builds runs through it. */
    val runtime: Runtime,
    /** What every process builds first ([ProcessGraph]): its files, clock, crypto, build and crash channel. */
    val process: ProcessGraph,
    /** The proof that this process's crash reporting started before this graph existed — and its started channel. */
    val started: StartedProcess,
    /**
     * The **composition lane** the runtime's scope runs on (`docs/architecture.md`, law "Dispatcher lanes are fixed by
     * the composition") — named by the root. The screen's lookups read on it: a command's synchronous prefix otherwise
     * runs on whichever thread fired it.
     */
    lane: CoroutineDispatcher,
) {
    /** The state whose changes start work, each handed to its entry point. */
    val observation: Observation = Observation(runtime)

    /** This process's services over [ports]. */
    val services: ServicesGraph = ServicesGraph(ports, process)

    /** The backend's protocol and the app's credential. */
    val backend: BackendGraph = BackendGraph(ports, process, services)

    /** The app's foreground life: whether it is in front now, and its returns, which the create draft follows. */
    val foreground: AppForeground = AppForeground(process.clock)

    /** The app's own memory readings, taken while it settles in the background. */
    val footprints: RecordFootprint = RecordFootprint(
        foreground,
        FootprintReading(ports.processInfo),
        process.footprints,
    )

    /** The latest selection, set only by the selection subscription the composition installs. */
    val selection: SelectionSnapshots = SelectionSnapshots()

    /** The photo library's selection observer and its fresh grant read. */
    val library: LibrarySelection = LibrarySelection(ports.gallery)

    /** The photo-library grant as it stands now, in the domain's words. */
    val grant: GrantRead = GrantRead(ports.gallery.grant)

    /** The rig's per-uploader switches — on in every shipped build. */
    val uploaderSwitches: UploaderSwitches = UploaderSwitches(ports.devControls)

    /** The OS's own uploader: whether it may be registered now and whether the OS confirms it is — read at use. */
    val osUploader: OsUploader = OsUploader(ports.extensionRegistry, uploaderSwitches, grant)

    /** Receiving: its store, the staging, the transfers, the library's import, and its use cases. */
    val receiving: ReceivingGraph =
        ReceivingGraph(ports, process, services, backend, foreground, runtime, selection, grant)

    /**
     * **The member's own photos** — the one place their contribution is decided (data's [OwnPhotos]): the upload cycle,
     * the status total, the join preview and the album gather all take their answer from this instance. Its walk sits
     * behind the walk memo, which is the app process's alone. Public for the control channel's gallery read.
     */
    val ownPhotos: OwnPhotos = OwnPhotos(
        gallery = ports.gallery,
        walk = MemoizedWalk(GalleryWalk(ports.gallery, services.wallClock), ports.gallery, services.wallClock),
        selection = selection,
        albums = DenylistedAlbums(ports.gallery),
        clock = services.wallClock,
        received = receiving.received,
    )

    /** The membership's use cases, queries and branch. */
    val membershipLayers: MembershipGraph = MembershipGraph(services, backend, lane)

    /** The event album's map, the library's album calls and the use cases over them. */
    val album: AlbumGraph = AlbumGraph(ports, services)

    /** The wakes the app asks for, and the re-arm each wake ends with. */
    val wake: WakeGraph = WakeGraph(ports, services, grant, osUploader)

    /** The app's uploader — the app-driven tier on every OS version, over the app's own session. */
    val uploader: UploaderGraph = UploaderGraph(
        ports,
        process,
        services,
        backend,
        ownPhotos,
        uploaderSwitches,
        receiving,
        album,
        osUploader,
        grant,
        wake,
    )

    /** The event album's gather and its grant follower. */
    val gather: AlbumGatherGraph = AlbumGatherGraph(
        runtime,
        membershipLayers,
        grant,
        receiving,
        uploader,
        ownPhotos,
        album,
    )

    /** The joined screen's status: the total, and how far sharing is, followed from the stores. */
    val status: StatusGraph = StatusGraph(services, receiving, ownPhotos, foreground, uploader, runtime)

    /** The photo-library grant, as the screen and host assembly follow it. */
    val photoGrant: PhotoGrantQuery = PhotoGrantQuery(PhotoGrants(ports.gallery.grant))

    val linkReading: LinkReading = LinkReading()

    /** The invite the app was last opened with, read, until a screen has opened it. */
    val openedInvites: OpenedInvites = OpenedInvites()

    /** The invite waiting to be opened, as the screen follows it. */
    val invitesQuery: OpenedInvitesQuery = OpenedInvitesQuery(openedInvites)

    /** The member's requests of the membership, each answered on its own status. */
    val membershipStatuses: MembershipStatuses = MembershipStatuses()

    /** The member's creates, each answered on this status with the minted event's id and key. */
    val creationStatus: RequestStatuses<CreateResult> = RequestStatuses()

    /** A bug report's status: what the member is told of the report they sent. */
    val reportStatus: RequestStatuses<ReportOutcome> = RequestStatuses()

    /** The join-time load: the joined event's rows made its share set from the device's stored-file listing. */
    private val shareSetLoad: LoadShareSet =
        LoadShareSet(DeviceListing(backend.gateway, services.deviceIdentity, services.servedNames), uploader.reset)

    /** Create an event: minted with its key, for the creator to join through the gate a scanned invite takes. */
    private val createEvent: CreateEvent =
        CreateEvent(
            EventCreation(backend.gateway, services.metaCipher),
            NewEventKeys(services.eventKeyIds, ports.devControls),
        )

    /** The transfer sessions' drain reports, which a relaunch for their events waits for. */
    val uploadDrains: SessionDrains = SessionDrains()
    val downloadDrains: SessionDrains = SessionDrains()

    /** The foreground-gated network watch and what it tells the member. */
    val network: NetworkGraph = NetworkGraph(runtime, ports.network)

    /** The registration a delivered token, a new credential and a join run. */
    val pushRegister: RegisterPush = RegisterPush(services.pushTokens, services.pushRecord, backend.push)

    /** Whether a silent push's wake joins the tail. */
    val pushTail: PushTailLookup = PushTailLookup(services.membership)

    /** The mobile-data choice's change, status and read. */
    val mobileData: MobileDataGraph = MobileDataGraph(services)

    /** What the screen reads of the joined event's key: whether it is held, and so the key its invite carries. */
    val keyQuery: EventKeyQuery = EventKeyQuery(services.membership, services.eventKeyStore)

    /** The join and settings surfaces' shareable count, a lookup the screen asks on the composition lane. */
    val shareableCount: ShareableCountLookup = ShareableCountLookup(ownPhotos, lane)

    /** What the app's entry points run. */
    val work: WorkGraph = WorkGraph(
        runtime,
        services,
        backend,
        uploader,
        receiving,
        album,
        gather,
        membershipLayers,
        wake,
        status,
        footprints,
        shareSetLoad,
        membershipStatuses,
        pushRegister,
        mobileData,
        grant,
    )

    /** The bug report's assembly. */
    private val diagnostics: DiagnosticsGraph =
        DiagnosticsGraph(ports, process, services, backend, grant, osUploader, uploader, receiving, selection)

    /** What the member can ask for, as the core's entry points. */
    val commands: CommandsGraph = CommandsGraph(
        runtime,
        ports,
        process,
        started,
        work,
        createEvent,
        creationStatus,
        diagnostics,
        reportStatus,
        openedInvites,
    )

    /** The app's push entry points — what the composition hands the push port, and a contract run hands back. */
    val pushHandlers: PushHandlers = PushHandlers(
        OnPushToken(runtime, services.pushTokens, pushRegister),
        OnPushTokenFailure(runtime),
        OnSilentPush(runtime, work.prelude, pushTail, work.planJoined, work.wakeWork),
    )

    /** A new device credential: the push registration is published again — the recovery for a refused one. */
    val credentialChanged: OnCredentialChanged = OnCredentialChanged(runtime, pushRegister)

    /** Every report names the event and the device it is about: stood on the channel at launch and at every change. */
    val membershipChanged: OnMembershipChanged = OnMembershipChanged(
        runtime,
        DescribeReporter(ReportedIdsRead(services.membership, services.deviceIdentity, process.build), started.crash),
    )

    /** One selection-change emission → ONE read serving both the status total and the enqueue. */
    val selectionChanged: OnSelectionChanged = OnSelectionChanged(
        runtime,
        work.selectionRecognitionPass,
        status.count,
        work.walkAndPublish,
        work.wakeWork,
    )

    /** The subscriptions every start installs, a background one included. */
    val startSubscriptions: StartSubscriptions = StartSubscriptions(
        observation,
        backend.attestationStatus.tokenChanged,
        credentialChanged,
        services.membership.changes,
        membershipChanged,
        selection,
        library,
        work.selectionRecognition,
        selectionChanged,
    )

    /** The rig moved its uploader pin (a rig build only): the extension follows it, then both are reconciled. */
    val uploaderPinMoved: UploaderPinMoved = UploaderPinMoved(uploader.registrar, uploaderSwitches, work.uploaderChange)

    // ── What the status host and the control channel read of the graph, as values ──────────────────────────────────
    val joinDetails: JoinDetailsLookup get() = membershipLayers.joinDetails
    val membership: StateFlow<MembershipShown> get() = membershipLayers.query.shown
    val sharingProgress: StateFlow<SharingProgressRead> get() = status.sharing.state
    val downloadProgress: StateFlow<DownloadProgress> get() = receiving.progress.progress
    val serviceFault: StateFlow<ServiceFault?> get() = backend.serviceConditions.fault
    val foregroundReturns: StateFlow<ForegroundReturn> get() = foreground.returns
    val inFront: StateFlow<InFront> get() = foreground.inFront
    val versionRefusal: StateFlow<VersionRefusal?> get() = backend.versionGate.refusal
    val attested: StateFlow<Boolean> get() = backend.attestationStatus.attested
    val refusal: StateFlow<DeviceRefusal?> get() = backend.attestationStatus.refusal
    val attestationFault: StateFlow<ServiceFault?> get() = backend.attestationStatus.fault
}
