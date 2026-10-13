package app.snapsync.compose

import app.snapsync.entrypoints.ForegroundWork
import app.snapsync.entrypoints.ImportReceived
import app.snapsync.entrypoints.OnDownloadStaged
import app.snapsync.entrypoints.OnKeyRestored
import app.snapsync.entrypoints.Prelude
import app.snapsync.entrypoints.Provision
import app.snapsync.entrypoints.TopUp
import app.snapsync.entrypoints.UploadEngine
import app.snapsync.entrypoints.UploadOwn
import app.snapsync.entrypoints.WakeWork
import app.snapsync.feature.status.ForegroundCounts
import app.snapsync.feature.upload.OwnUploads
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
    internal val prelude: Prelude by lazy { Prelude(core.services.config, core.attestation) }

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

    internal val wakeWork: WakeWork by lazy {
        WakeWork(
            prelude,
            importReceived,
            uploadOwn,
            wakeRearm,
            core.membershipEnd.completion,
            core.downloadController,
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
            core.membershipRefresh,
            core.backend.directory,
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

    /** A lost event key kept from a reopened invite: the foreground's work runs again. */
    internal val keyRestored: OnKeyRestored by lazy { OnKeyRestored(core.runtime, foregroundWork, wakeWork) }

    /** The join's provision: entering the membership, then what follows a join. */
    internal val provision: Provision by lazy {
        Provision(
            // The order is `MembershipEntry`'s rule; the backend leave is awaited here, unlike the leave command's.
            core.membershipEntry(core.leaveEvent::notifySwitchLeave),
            core.services.config,
            core.statusRefresh,
            core.albumCoordinator,
            core.galleryAccess,
            core.downloadController,
            core.joinUnion,
            core.pushRegistration,
            core.services.pushTokens,
        )
    }
}
