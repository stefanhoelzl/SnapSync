package app.snapsync.composition.app

import app.snapsync.application.membership.LoadShareSet
import app.snapsync.application.process.RecordFootprint
import app.snapsync.application.push.RegisterPush
import app.snapsync.application.receiving.RecognitionAsk
import app.snapsync.data.gallery.GrantRead
import app.snapsync.entrypoints.AfterJoin
import app.snapsync.entrypoints.AfterSettings
import app.snapsync.entrypoints.DeletedTransfers
import app.snapsync.entrypoints.EndOfWake
import app.snapsync.entrypoints.Enrolling
import app.snapsync.entrypoints.EventAlbum
import app.snapsync.entrypoints.ForegroundReceiving
import app.snapsync.entrypoints.ForegroundWork
import app.snapsync.entrypoints.HeldCheck
import app.snapsync.entrypoints.ImportReceived
import app.snapsync.entrypoints.Joining
import app.snapsync.entrypoints.Leaving
import app.snapsync.entrypoints.OnDownloadStaged
import app.snapsync.entrypoints.OnGrantChanged
import app.snapsync.entrypoints.OnInviteForOtherEvent
import app.snapsync.entrypoints.OnJoinConfirmed
import app.snapsync.entrypoints.OnKeyRestoreRequested
import app.snapsync.entrypoints.OnLeaveConfirmed
import app.snapsync.entrypoints.OnMembershipReadRetried
import app.snapsync.entrypoints.OnMobileDataChanged
import app.snapsync.entrypoints.OnRenameRequested
import app.snapsync.entrypoints.OnSelectionAnswerable
import app.snapsync.entrypoints.OnSettingsChanged
import app.snapsync.entrypoints.OnUploaderChange
import app.snapsync.entrypoints.PhotoCheck
import app.snapsync.entrypoints.PlanJoined
import app.snapsync.entrypoints.Prelude
import app.snapsync.entrypoints.ReceivedPositionReport
import app.snapsync.entrypoints.Recognition
import app.snapsync.entrypoints.SelectionRecognition
import app.snapsync.entrypoints.SettleShare
import app.snapsync.entrypoints.StateRefresh
import app.snapsync.entrypoints.SweepLeftovers
import app.snapsync.entrypoints.TopUp
import app.snapsync.entrypoints.UploadOwn
import app.snapsync.entrypoints.UploaderArm
import app.snapsync.entrypoints.WakeWork
import app.snapsync.entrypoints.WalkAndPublish
import app.snapsync.query.membership.MembershipStatuses
import app.snapsync.scheduling.Runtime

/**
 * What the app's entry points run (`core/entrypoints`, `docs/patterns.md`): the work a wake and the foreground share,
 * the sequences they run single-flight, and the in-process requests. The layer above the app's graph that orders it,
 * which the composition hands to the event ports.
 */
class WorkGraph(
    runtime: Runtime,
    services: ServicesGraph,
    backend: BackendGraph,
    uploader: UploaderGraph,
    receiving: ReceivingGraph,
    album: AlbumGraph,
    gather: AlbumGatherGraph,
    membership: MembershipGraph,
    wake: WakeGraph,
    status: StatusGraph,
    footprints: RecordFootprint,
    shareSetLoad: LoadShareSet,
    statuses: MembershipStatuses,
    register: RegisterPush,
    mobileData: MobileDataGraph,
    grant: GrantRead,
) {
    /** The top-up: the one sequence that creates the app's upload jobs. */
    val topUp: TopUp = TopUp(
        uploader.layers.gate,
        uploader.layers.contribute,
        uploader.layers.plan,
        uploader.createJobs,
        uploader.layers.album,
    )

    /** The walk → publish: a wake's under a full grant, a selection change's, and the end of a wake's settle. */
    val walkAndPublish: WalkAndPublish = WalkAndPublish(
        uploader.layers.gate,
        uploader.layers.contribute,
        uploader.layers.walk,
        uploader.layers.recordWalk,
        uploader.layers.album,
        uploader.layers.publish,
    )

    private val uploadOwn: UploadOwn = UploadOwn(topUp, walkAndPublish, grant)

    val prelude: Prelude = Prelude(services.membership, services.eventKeyStore, backend.refreshAttestation)

    /** A recognition pass before a drain imports, or once a grant became usable. */
    private val recognitionBeforeImporting: Recognition = Recognition(
        membership.branch,
        receiving.recognitionNeeded,
        receiving.recognize,
        RecognitionAsk.BEFORE_IMPORTING,
    )

    /** A recognition pass after a selection was published. */
    val selectionRecognitionPass: Recognition = Recognition(
        membership.branch,
        receiving.recognitionNeeded,
        receiving.recognize,
        RecognitionAsk.AFTER_SELECTION,
    )

    val importReceived: ImportReceived = ImportReceived(
        membership.branch,
        recognitionBeforeImporting,
        receiving.importReady,
        album.forImport,
        album.markFilled,
        receiving.importNext,
    )

    /** The event album a join or a settings change makes and gathers into. */
    private val eventAlbum: EventAlbum = EventAlbum(album.ensure, gather.gathering, grant)

    /** Plan what the joined membership receives — a push's, an opening's. */
    val planJoined: PlanJoined = PlanJoined(membership.branch, receiving.receivingOf, receiving.plan)

    private val stateRefresh: StateRefresh =
        StateRefresh(membership.branch, DeletedTransfers(uploader.stop, receiving.stop))

    /** What this device received, told its event: at a wake's end, after an import outside one, and before a leave. */
    private val receivedReport: ReceivedPositionReport =
        ReceivedPositionReport(membership.branch, receiving.receivingOf, receiving.holds, receiving.report)

    private val endOfWake: EndOfWake = EndOfWake(
        membership.branch,
        PhotoCheck(membership.branch, receiving.receivingOf, receiving.planIfDue, receiving.plan),
        membership.deliver,
        // The uploader's own walk → publish, owed once the range has ended: its look settles the share. Under a partial
        // grant it reads the selection snapshot, never the library.
        SettleShare(membership.branch, uploader.layers.settleNeed, walkAndPublish),
        membership.claimState,
        stateRefresh,
        receivedReport,
    )

    val wakeWork: WakeWork =
        WakeWork(prelude, importReceived, uploadOwn, wake.rearm, receiving.counts, endOfWake, footprints)

    /** What a membership transition arms the app's uploader with. */
    private val uploaderArm: UploaderArm = UploaderArm(runtime, wakeWork, wake.arming)

    /** The app launched with its UI, or the grant really changed. */
    val uploaderChange: OnUploaderChange = OnUploaderChange(runtime, uploader.reconcile, uploaderArm)

    val foregroundWork: ForegroundWork = ForegroundWork(
        prelude,
        membership.query,
        uploader.sweep,
        uploader.settleStored,
        status.count,
        ForegroundReceiving(planJoined, receiving.sweepUnclaimed, receiving.releaseImported),
        stateRefresh,
        SweepLeftovers(
            membership.branch,
            services.eventKeyStore,
            receiving.stop,
            uploader.stop,
            services.membershipChange,
        ),
    )

    /** A staging recorded in a running process asks for the import, then tells what it added. */
    val downloadStaged: OnDownloadStaged = OnDownloadStaged(runtime, importReceived, receivedReport)

    /** The photo grant changed after the launch: the uploads follow it, and the received photos are recognised. */
    val grantChanged: OnGrantChanged = OnGrantChanged(runtime, uploaderChange, recognitionBeforeImporting, status.count)

    /** A selection about to be published withholds the recognition its unrecognised marked photos need. */
    val selectionRecognition: SelectionRecognition = SelectionRecognition(membership.query, receiving.withhold)

    /** The process's one recovery of what a dead process's imports left, once the grant can be read. */
    val selectionAnswerable: OnSelectionAnswerable =
        OnSelectionAnswerable(runtime, receiving.settleInterrupted, importReceived)

    /** A reopened invite of the joined event offered its key back. */
    val keyRestoreRequested: OnKeyRestoreRequested =
        OnKeyRestoreRequested(runtime, membership.restoreKey, foregroundWork, wakeWork, statuses)

    /** The member tried again to read whether this device is in an event. */
    val membershipReadRetried: OnMembershipReadRetried =
        OnMembershipReadRetried(runtime, prelude, foregroundWork, wakeWork, statuses)

    /** The member confirmed a join. */
    val joinConfirmed: OnJoinConfirmed = OnJoinConfirmed(
        runtime,
        membership.enroll,
        Enrolling(
            services.eventKeyStore,
            membership.enroll,
            Joining(
                services.membershipChange,
                shareSetLoad,
                receiving.forgetOtherEvents,
                receiving.recognize,
                membership.enter,
                AfterJoin(
                    uploader.reconcile,
                    uploaderArm,
                    status.count,
                    eventAlbum,
                    receiving.receivingOf,
                    receiving.plan,
                    register,
                    statuses,
                ),
                statuses,
            ),
            statuses,
        ),
        statuses,
    )

    /** The member left. */
    val leaveConfirmed: OnLeaveConfirmed = OnLeaveConfirmed(
        runtime,
        receivedReport,
        membership.leave,
        Leaving(uploader.stop, receiving.stop, services.eventKeyStore, membership.deliver),
    )

    /** Another event's invite loaded while joined. */
    val inviteForOtherEvent: OnInviteForOtherEvent = OnInviteForOtherEvent(
        runtime,
        membership.switchLookup,
        HeldCheck(membership.branch, receiving.receivingOf, receiving.holds),
        statuses,
    )

    /** The member changed the joined event's settings. */
    val settingsChanged: OnSettingsChanged = OnSettingsChanged(
        runtime,
        membership.branch,
        membership.reconfigure,
        AfterSettings(
            status.count,
            uploader.reconcile,
            uploaderArm,
            eventAlbum,
            receiving.receivingOf,
            receiving.plan,
            receiving.stop,
            receiving.report,
            statuses,
        ),
        statuses,
    )

    /** The member renamed the joined event. */
    val renameRequested: OnRenameRequested = OnRenameRequested(runtime, membership.branch, membership.rename, statuses)

    /** The member switched whether photos may use mobile data. */
    val mobileDataChanged: OnMobileDataChanged =
        OnMobileDataChanged(runtime, mobileData.set, services.mobileData, mobileData.statuses, wakeWork)
}
