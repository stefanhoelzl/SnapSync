package app.snapsync.compose

import app.snapsync.application.receiving.ForgetOtherEvents
import app.snapsync.application.receiving.FreeTransfer
import app.snapsync.application.receiving.HoldsEverything
import app.snapsync.application.receiving.ImportNextStaged
import app.snapsync.application.receiving.ImportReady
import app.snapsync.application.receiving.PlanIfDue
import app.snapsync.application.receiving.PlanReceiving
import app.snapsync.application.receiving.ReceivingOf
import app.snapsync.application.receiving.RecognitionNeeded
import app.snapsync.application.receiving.RecognizeReceivedPhotos
import app.snapsync.application.receiving.RecordStaged
import app.snapsync.application.receiving.ReleaseImported
import app.snapsync.application.receiving.ReportReceivedPosition
import app.snapsync.application.receiving.SettleInterruptedImports
import app.snapsync.application.receiving.StageDownload
import app.snapsync.application.receiving.StopReceiving
import app.snapsync.application.receiving.SweepUnclaimedStaging
import app.snapsync.application.receiving.WithholdRecognition
import app.snapsync.data.crypto.DownloadOpening
import app.snapsync.data.receiving.DownloadDatabaseFile
import app.snapsync.data.receiving.DownloadStore
import app.snapsync.data.receiving.DownloadTransfers
import app.snapsync.data.receiving.EventUnion
import app.snapsync.data.receiving.ImportMarkerWrites
import app.snapsync.data.receiving.ImportQueue
import app.snapsync.data.receiving.ImportedReleases
import app.snapsync.data.receiving.LibraryImport
import app.snapsync.data.receiving.LibraryPresence
import app.snapsync.data.receiving.MarkedPhotos
import app.snapsync.data.receiving.ReceivedPhotos
import app.snapsync.data.receiving.ReceivedReporting
import app.snapsync.data.receiving.ReceivingCleanup
import app.snapsync.data.receiving.ReceivingCounts
import app.snapsync.data.receiving.ReceivingPlans
import app.snapsync.data.receiving.RecognitionPasses
import app.snapsync.data.receiving.RecognitionRecords
import app.snapsync.data.receiving.ReportedReceived
import app.snapsync.data.receiving.StagingArea
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.query.receiving.ReceivingProgress

/**
 * Receiving in the app process (`docs/architecture.md`, "The layers"): its store over `downloads.db` — the app its one
 * writer and migrator — the staging area, the transfers, the library's import and presence, the union read, and the
 * use cases the entry points order. Each built once, over [core], on first touch: a background relaunch that only
 * delivers a session's events builds the store and the staging, and nothing else.
 */
internal class ReceivingLayers(private val core: AppCore) {
    /** The download store's file — also what another process's commit to it is announced on. */
    @OptIn(AppProcessOnly::class)
    val file: DownloadDatabaseFile by lazy { DownloadDatabaseFile(core.ports.databases) }

    private val staging: StagingArea by lazy { StagingArea(core.process.files) }

    private val transfers: DownloadTransfers by lazy {
        DownloadTransfers(core.ports.download, core.services.mobileData, core.exchange)
    }

    @OptIn(AppProcessOnly::class)
    private val store: DownloadStore by lazy { DownloadStore(file, staging, transfers) }

    private val plans: ReceivingPlans by lazy { ReceivingPlans(store) }
    private val queue: ImportQueue by lazy { ImportQueue(store) }
    private val releases: ImportedReleases by lazy { ImportedReleases(store) }
    private val recognitionRecords: RecognitionRecords by lazy { RecognitionRecords(store) }
    private val cleanup: ReceivingCleanup by lazy { ReceivingCleanup(store) }

    /** How far this device has received — the counts its status and the old zones read. */
    val counts: ReceivingCounts by lazy { ReceivingCounts(store) }

    /** The received photos the app's sharing reads — and the recognition record its passes write. */
    val received: ReceivedPhotos by lazy { ReceivedPhotos(file) }

    /** What the library's import blocks write, inline. */
    val markers: ImportMarkerWrites by lazy { ImportMarkerWrites(file) }

    /** The joined screen's download progress. */
    val progress: ReceivingProgress by lazy {
        ReceivingProgress(counts, core.services.membership, core.foreground, file.changes, core.scope)
    }

    private val opening: DownloadOpening by lazy {
        DownloadOpening(
            core.services.membership,
            core.services.eventKeyStore,
            core.services.fileCipher,
            core.process.files,
        )
    }

    private val union: EventUnion by lazy { EventUnion(core.backend.gateway, core.services.manifestRecord) }
    private val marked: MarkedPhotos by lazy { MarkedPhotos(core.ports.gallery, core.selection) }
    private val passes = RecognitionPasses()

    val receivingOf: ReceivingOf by lazy { ReceivingOf(core.services.eventKeyStore) }

    val plan: PlanReceiving by lazy {
        PlanReceiving(plans, union, core.services.deviceIdentity, core.services.eventChecks, core.services.wallClock)
    }

    val planIfDue: PlanIfDue by lazy { PlanIfDue(core.services.eventChecks, core.services.wallClock) }
    val stage: StageDownload by lazy { StageDownload(staging, opening) }
    val record: RecordStaged by lazy { RecordStaged(queue, opening) }
    val free: FreeTransfer by lazy { FreeTransfer(queue) }
    val importNext: ImportNextStaged by lazy {
        ImportNextStaged(queue, releases, LibraryImport(core.ports.gallery, staging))
    }
    val releaseImported: ReleaseImported by lazy { ReleaseImported(releases) }
    val sweepUnclaimed: SweepUnclaimedStaging by lazy { SweepUnclaimedStaging(cleanup) }

    val settleInterrupted: SettleInterruptedImports by lazy {
        SettleInterruptedImports(releases, LibraryPresence(core.ports.gallery, core.selection))
    }

    val recognize: RecognizeReceivedPhotos by lazy {
        RecognizeReceivedPhotos(recognitionRecords, union, received, marked, core.services.deviceIdentity, passes)
    }

    val recognitionNeeded: RecognitionNeeded by lazy { RecognitionNeeded(passes, received, core.grant) }
    val withhold: WithholdRecognition by lazy { WithholdRecognition(marked, received, passes) }
    val forgetOtherEvents: ForgetOtherEvents by lazy { ForgetOtherEvents(recognitionRecords, passes) }
    val importReady: ImportReady by lazy { ImportReady(received, core.grant) }
    val holds: HoldsEverything by lazy { HoldsEverything(counts, union, core.services.deviceIdentity) }

    val report: ReportReceivedPosition by lazy {
        ReportReceivedPosition(
            ReceivedReporting(core.backend.gateway, core.services.deviceIdentity),
            ReportedReceived(),
            core.services.wallClock,
            core.services.membership,
        )
    }

    val stop: StopReceiving by lazy { StopReceiving(cleanup) }
}
