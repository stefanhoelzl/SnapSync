package app.snapsync.compose

import app.snapsync.application.receiving.RecognitionAsk
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

/**
 * What the app's entry points run (`domain/entrypoints`, `docs/patterns.md`): the work a wake and the foreground share,
 * the sequences they run single-flight, and the in-process requests — each built once, over [core], on first touch.
 * The core's own graph is [AppCore]'s; this is the layer above it that orders it, which [AppCore.inbound] hands to the
 * event ports.
 */
internal class AppWork(private val core: AppCore) {
    private val uploads get() = core.appUploader

    /** The top-up: the one sequence that creates the app's upload jobs. */
    internal val topUp: TopUp by lazy {
        val layers = uploads.layers
        TopUp(layers.gate, layers.contribute, layers.plan, uploads.createJobs, layers.album)
    }

    /** The walk → publish: a wake's under a full grant, a selection change's, and the end of a wake's settle. */
    internal val walkAndPublish: WalkAndPublish by lazy {
        val layers = uploads.layers
        WalkAndPublish(layers.gate, layers.contribute, layers.walk, layers.recordWalk, layers.album, layers.publish)
    }

    private val uploadOwn: UploadOwn by lazy { UploadOwn(topUp, walkAndPublish, core.grant) }

    /** What a membership transition arms the app's uploader with. */
    private val uploaderArm: UploaderArm by lazy { UploaderArm(core.runtime, wakeWork, wake.arming) }

    /** The app launched with its UI, or the grant really changed. */
    internal val uploaderChange: OnUploaderChange by lazy {
        OnUploaderChange(
            core.runtime,
            uploads.reconcile,
            uploaderArm,
        )
    }

    private val receiving get() = core.receiving

    /** A recognition pass before a drain imports, or once a grant became usable. */
    private val recognitionBeforeImporting: Recognition by lazy {
        Recognition(
            membership.branch,
            receiving.recognitionNeeded,
            receiving.recognize,
            RecognitionAsk.BEFORE_IMPORTING,
        )
    }

    /** A recognition pass after a selection was published. */
    internal val selectionRecognitionPass: Recognition by lazy {
        Recognition(membership.branch, receiving.recognitionNeeded, receiving.recognize, RecognitionAsk.AFTER_SELECTION)
    }

    internal val importReceived: ImportReceived by lazy {
        ImportReceived(
            membership.branch,
            recognitionBeforeImporting,
            receiving.importReady,
            core.album.forImport,
            core.album.markFilled,
            receiving.importNext,
        )
    }

    private val stopUploads by lazy { uploads.stop(wake.arming) }

    /** The event album a join or a settings change makes and gathers into. */
    private val eventAlbum: EventAlbum by lazy {
        EventAlbum(
            core.album.ensure,
            core.album.gathering,
            core.grant,
        )
    }

    internal val prelude: Prelude by lazy {
        Prelude(
            core.services.membership,
            core.services.eventKeyStore,
            core.refreshAttestation,
        )
    }

    /** The wakes the app asks for, and the re-arm each wake ends with. */
    private val wake: WakeLayers = WakeLayers(core)

    /** The membership's use cases, queries and branch. */
    internal val membership: MembershipLayers = MembershipLayers(core)

    private val statuses get() = core.membershipStatuses

    /** Plan what the joined membership receives — a push's, an opening's. */
    internal val planJoined: PlanJoined by lazy { PlanJoined(membership.branch, receiving.receivingOf, receiving.plan) }

    private val stateRefresh: StateRefresh by lazy {
        StateRefresh(membership.branch, DeletedTransfers(stopUploads, receiving.stop))
    }

    private val endOfWake: EndOfWake by lazy {
        EndOfWake(
            membership.branch,
            PhotoCheck(membership.branch, receiving.receivingOf, receiving.planIfDue, receiving.plan),
            membership.deliver,
            // The uploader's own walk → publish, owed once the range has ended: its look settles the share. Under a
            // partial grant it reads the selection snapshot, never the library.
            SettleShare(membership.branch, uploads.layers.settleNeed, walkAndPublish),
            membership.claimState,
            stateRefresh,
            ReceivedPositionReport(membership.branch, receiving.receivingOf, receiving.holds, receiving.report),
        )
    }

    internal val wakeWork: WakeWork by lazy {
        WakeWork(
            prelude,
            importReceived,
            uploadOwn,
            wake.rearm,
            receiving.counts,
            endOfWake,
            core.footprints,
        )
    }

    internal val foregroundWork: ForegroundWork by lazy {
        ForegroundWork(
            prelude,
            membership.query,
            uploads.sweep,
            uploads.settleStored,
            core.status.count,
            ForegroundReceiving(planJoined, receiving.sweepUnclaimed, receiving.releaseImported),
            stateRefresh,
            SweepLeftovers(
                membership.branch,
                core.services.eventKeyStore,
                receiving.stop,
                stopUploads,
                core.services.transition,
            ),
        )
    }

    /** A staging recorded in a running process asks for the import, and nothing else. */
    internal val downloadStaged: OnDownloadStaged by lazy { OnDownloadStaged(core.runtime, importReceived) }

    /** The photo grant changed after the launch: the uploads follow it, and the received photos are recognised. */
    internal val grantChanged: OnGrantChanged by lazy {
        OnGrantChanged(core.runtime, uploaderChange, recognitionBeforeImporting, core.status.count)
    }

    /** A selection about to be published withholds the recognition its unrecognised marked photos need. */
    internal val selectionRecognition: SelectionRecognition by lazy {
        SelectionRecognition(membership.query, receiving.withhold)
    }

    /** The process's one recovery of what a dead process's imports left, once the grant can be read. */
    internal val selectionAnswerable: OnSelectionAnswerable by lazy {
        OnSelectionAnswerable(core.runtime, receiving.settleInterrupted, importReceived)
    }

    /** A reopened invite of the joined event offered its key back. */
    internal val keyRestoreRequested: OnKeyRestoreRequested by lazy {
        OnKeyRestoreRequested(core.runtime, membership.restoreKey, foregroundWork, wakeWork, statuses)
    }

    /** The member tried again to read whether this device is in an event. */
    internal val membershipReadRetried: OnMembershipReadRetried by lazy {
        OnMembershipReadRetried(core.runtime, prelude, foregroundWork, wakeWork, statuses)
    }

    /** The member confirmed a join. */
    internal val joinConfirmed: OnJoinConfirmed by lazy {
        OnJoinConfirmed(
            core.runtime,
            membership.enroll,
            Enrolling(
                core.services.eventKeyStore,
                membership.enroll,
                Joining(
                    core.services.transition,
                    core.shareSetLoad,
                    receiving.forgetOtherEvents,
                    receiving.recognize,
                    membership.enter,
                    AfterJoin(
                        uploads.reconcile,
                        uploaderArm,
                        core.status.count,
                        eventAlbum,
                        receiving.receivingOf,
                        receiving.plan,
                        core.push.register,
                        statuses,
                    ),
                    statuses,
                ),
                statuses,
            ),
            statuses,
        )
    }

    /** The member left. */
    internal val leaveConfirmed: OnLeaveConfirmed by lazy {
        OnLeaveConfirmed(
            core.runtime,
            membership.leave,
            Leaving(stopUploads, receiving.stop, core.services.eventKeyStore, membership.deliver),
        )
    }

    /** Another event's invite loaded while joined. */
    internal val inviteForOtherEvent: OnInviteForOtherEvent by lazy {
        OnInviteForOtherEvent(
            core.runtime,
            membership.switchLookup,
            HeldCheck(membership.branch, receiving.receivingOf, receiving.holds),
            statuses,
        )
    }

    /** The member changed the joined event's settings. */
    internal val settingsChanged: OnSettingsChanged by lazy {
        OnSettingsChanged(
            core.runtime,
            membership.branch,
            membership.reconfigure,
            AfterSettings(
                core.status.count,
                uploads.reconcile,
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
    }

    /** The member renamed the joined event. */
    internal val renameRequested: OnRenameRequested by lazy {
        OnRenameRequested(core.runtime, membership.branch, membership.rename, statuses)
    }

    /** The member switched whether photos may use mobile data. */
    internal val mobileDataChanged: OnMobileDataChanged by lazy {
        OnMobileDataChanged(
            core.runtime,
            core.mobileData.set,
            core.services.mobileData,
            core.mobileData.statuses,
            wakeWork,
        )
    }
}
