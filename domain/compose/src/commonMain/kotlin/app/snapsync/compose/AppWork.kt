package app.snapsync.compose

import app.snapsync.entrypoints.AfterJoin
import app.snapsync.entrypoints.AfterSettings
import app.snapsync.entrypoints.DeletedTransfers
import app.snapsync.entrypoints.EndOfWake
import app.snapsync.entrypoints.Enrolling
import app.snapsync.entrypoints.EventAlbum
import app.snapsync.entrypoints.ForegroundWork
import app.snapsync.entrypoints.ImportReceived
import app.snapsync.entrypoints.Joining
import app.snapsync.entrypoints.Leaving
import app.snapsync.entrypoints.OnDevReset
import app.snapsync.entrypoints.OnDownloadStaged
import app.snapsync.entrypoints.OnInviteForOtherEvent
import app.snapsync.entrypoints.OnJoinConfirmed
import app.snapsync.entrypoints.OnKeyRestoreRequested
import app.snapsync.entrypoints.OnLeaveConfirmed
import app.snapsync.entrypoints.OnRenameRequested
import app.snapsync.entrypoints.OnSettingsChanged
import app.snapsync.entrypoints.Prelude
import app.snapsync.entrypoints.StateRefresh
import app.snapsync.entrypoints.SweepLeftovers
import app.snapsync.entrypoints.TopUp
import app.snapsync.entrypoints.UploadEngine
import app.snapsync.entrypoints.UploadOwn
import app.snapsync.entrypoints.WakeWork
import app.snapsync.feature.download.ReceivedReport
import app.snapsync.feature.status.ForegroundCounts
import app.snapsync.feature.upload.OwnUploads
import app.snapsync.feature.upload.ShareSettlement
import app.snapsync.feature.upload.WakeRearm
import app.snapsync.feature.upload.cadenceFacts
import app.snapsync.services.wake.Heartbeat

/**
 * What the app's entry points run (`domain/entrypoints`, `docs/patterns.md`): the work a wake and the foreground share,
 * the sequences they run single-flight, and the in-process requests — each built once, over [core], on first touch.
 * The core's own graph is [AppCore]'s; this is the layer above it that orders it, which [AppCore.inbound] hands to the
 * event ports.
 */
internal class AppWork(private val core: AppCore) {
    /** The app's own uploads as the wake's sequences run them. */
    internal val ownUploads: OwnUploads by lazy {
        OwnUploads(
            core.appUploader.cycle,
            core.galleryAccess,
            core.appUploadAdmission,
        )
    }

    private val foregroundCounts: ForegroundCounts by lazy { ForegroundCounts(core.foreground, core.ledgerCounts) }
    internal val importReceived: ImportReceived by lazy { ImportReceived(core.downloadController, foregroundCounts) }
    internal val topUp: TopUp by lazy { TopUp(ownUploads, foregroundCounts) }
    private val uploadOwn: UploadOwn by lazy { UploadOwn(ownUploads, foregroundCounts) }

    /** The event album a join or a settings change makes and gathers into. */
    private val eventAlbum: EventAlbum by lazy {
        EventAlbum(
            core.albumCoordinator,
            core.albumGather,
            core.galleryAccess,
        )
    }

    internal val prelude: Prelude by lazy {
        Prelude(
            core.services.membership,
            core.services.eventKeyStore,
            core.refreshAttestation,
        )
    }

    /** The heartbeat the wake's re-arm keeps pending, and a disarm cancels. */
    private val heartbeat: Heartbeat by lazy {
        Heartbeat(core.ports.wake, transferNetwork = core.services.mobileData::transferNetwork, log = core.services.log)
    }

    private val wakeRearm: WakeRearm by lazy {
        WakeRearm(
            heartbeat,
            core.services.downloadStore,
            facts = {
                cadenceFacts(
                    core.services.config,
                    core.galleryAccess.grant.value,
                    core.extensionRegistrableNow(),
                    core.extensionRegistration,
                )
            },
            log = core.services.log,
        )
    }

    /** The membership's use cases, queries and branch. */
    internal val membership: MembershipLayers = MembershipLayers(core)

    private val statuses get() = core.membershipStatuses

    private val stateRefresh: StateRefresh by lazy {
        StateRefresh(membership.branch, DeletedTransfers(core.uploadTransitions, core.downloadController))
    }

    private val endOfWake: EndOfWake by lazy {
        EndOfWake(
            membership.branch,
            core.downloadController,
            membership.deliver,
            ShareSettlement(
                core.services.config,
                core.services.manifestStore,
                // The uploader's own discovery → publish, which marks the manifest settled once the range has ended.
                // Under a partial grant it reads the selection snapshot, never the library.
                publishFinal = { core.appUploader.cycle.walkAndPublish() },
            ),
            membership.claimState,
            stateRefresh,
            ReceivedReport(core.services.config, core.downloadController, core.backend.received),
        )
    }

    internal val wakeWork: WakeWork by lazy {
        WakeWork(
            prelude,
            importReceived,
            uploadOwn,
            wakeRearm,
            endOfWake,
            core.footprints,
        )
    }

    internal val foregroundWork: ForegroundWork by lazy {
        ForegroundWork(
            prelude,
            core.services.config,
            core.network.watches,
            core.uploadTransfer,
            storedUploadSettleFor(core.services, core.backend.deviceFiles),
            core.statusRefresh,
            core.downloadController,
            stateRefresh,
            SweepLeftovers(
                membership.branch,
                core.services.eventKeyStore,
                core.downloadController,
                core.uploadTransitions,
                core.services.transition,
            ),
        )
    }

    /** The uploads' arm — what a membership transition starts its uploads with. */
    internal val uploadEngine: UploadEngine by lazy {
        UploadEngine(
            core.runtime,
            wakeWork,
            heartbeat,
            core.uploadTransfer,
        )
    }

    /** A staging recorded in a running process asks for the import, and nothing else. */
    internal val downloadStaged: OnDownloadStaged by lazy { OnDownloadStaged(core.runtime, importReceived) }

    /** A reopened invite of the joined event offered its key back. */
    internal val keyRestoreRequested: OnKeyRestoreRequested by lazy {
        OnKeyRestoreRequested(core.runtime, membership.restoreKey, foregroundWork, wakeWork, statuses)
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
                    core.joinUnion,
                    core.shareSetLoad,
                    core.receivedPhotoAdoption,
                    membership.enter,
                    AfterJoin(
                        core.uploadTransitions,
                        core.statusRefresh,
                        eventAlbum,
                        core.downloadController,
                        core.joinUnion,
                        core.pushRegistration,
                        core.services.pushTokens,
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
            Leaving(core.uploadTransitions, core.downloadController, core.services.eventKeyStore, membership.deliver),
        )
    }

    /** Another event's invite loaded while joined. */
    internal val inviteForOtherEvent: OnInviteForOtherEvent by lazy {
        OnInviteForOtherEvent(
            core.runtime,
            membership.switchLookup,
            core.downloadController,
            core.services.config,
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
                core.statusRefresh,
                core.uploadTransitions,
                eventAlbum,
                core.downloadController,
                statuses,
            ),
            statuses,
        )
    }

    /** The member renamed the joined event. */
    internal val renameRequested: OnRenameRequested by lazy {
        OnRenameRequested(core.runtime, membership.branch, membership.rename, statuses)
    }

    /** The control channel voids this device's durable state (a rig build only). */
    internal val devReset: OnDevReset by lazy {
        OnDevReset(
            core.runtime,
            core.services.ledger,
            core.downloadController,
            membership.forget,
            core.services.eventKeyStore,
            core.services.mobileData,
        )
    }
}
