package app.snapsync.composition.app

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
import app.snapsync.composition.process.ProcessGraph
import app.snapsync.data.crypto.DownloadOpening
import app.snapsync.data.foreground.AppForeground
import app.snapsync.data.gallery.GrantRead
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
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.query.receiving.ReceivingProgress
import app.snapsync.scheduling.Runtime

/**
 * Receiving in the app process (`docs/architecture.md`, "The layers"): its store over `downloads.db` — the app its one
 * writer and migrator — the staging area, the transfers, the library's import and presence, the union read, and the
 * use cases the entry points order. Nothing here opens the store as it is built.
 */
@OptIn(AppProcessOnly::class)
class ReceivingGraph(
    ports: AppPorts,
    process: ProcessGraph,
    services: ServicesGraph,
    backend: BackendGraph,
    foreground: AppForeground,
    runtime: Runtime,
    selection: SelectionSnapshots,
    grant: GrantRead,
) {
    /** The download store's file — also what another process's commit to it is announced on. */
    val file: DownloadDatabaseFile = DownloadDatabaseFile(ports.databases)
    private val staging: StagingArea = StagingArea(process.files)
    private val transfers: DownloadTransfers = DownloadTransfers(ports.download, services.mobileData, backend.exchange)
    private val store: DownloadStore = DownloadStore(file, staging, transfers)
    private val plans: ReceivingPlans = ReceivingPlans(store)
    private val queue: ImportQueue = ImportQueue(store)
    private val releases: ImportedReleases = ImportedReleases(store)
    private val recognitionRecords: RecognitionRecords = RecognitionRecords(store)
    private val cleanup: ReceivingCleanup = ReceivingCleanup(store)

    /** How far this device has received — the counts its status reads. */
    val counts: ReceivingCounts = ReceivingCounts(store)

    /** The received photos the app's sharing reads — and the recognition record its passes write. */
    val received: ReceivedPhotos = ReceivedPhotos(file)

    /** What the library's import blocks write, inline. */
    val markers: ImportMarkerWrites = ImportMarkerWrites(file)

    /** The joined screen's download progress. */
    val progress: ReceivingProgress =
        ReceivingProgress(counts, services.membership, foreground, file.changes, runtime.scope)

    private val opening: DownloadOpening =
        DownloadOpening(services.membership, services.eventKeyStore, services.fileCipher, process.files)
    private val union: EventUnion = EventUnion(backend.gateway, services.manifestRecord)
    private val marked: MarkedPhotos = MarkedPhotos(ports.gallery, selection)
    private val passes: RecognitionPasses = RecognitionPasses()

    val receivingOf: ReceivingOf = ReceivingOf(services.eventKeyStore)
    val plan: PlanReceiving =
        PlanReceiving(plans, union, services.deviceIdentity, services.eventChecks, services.wallClock)
    val planIfDue: PlanIfDue = PlanIfDue(services.eventChecks, services.wallClock)
    val stage: StageDownload = StageDownload(staging, opening)
    val record: RecordStaged = RecordStaged(queue, opening)
    val free: FreeTransfer = FreeTransfer(queue)
    val importNext: ImportNextStaged = ImportNextStaged(queue, releases, LibraryImport(ports.gallery, staging))
    val releaseImported: ReleaseImported = ReleaseImported(releases)
    val sweepUnclaimed: SweepUnclaimedStaging = SweepUnclaimedStaging(cleanup)
    val settleInterrupted: SettleInterruptedImports =
        SettleInterruptedImports(releases, LibraryPresence(ports.gallery, selection))
    val recognize: RecognizeReceivedPhotos =
        RecognizeReceivedPhotos(recognitionRecords, union, received, marked, services.deviceIdentity, passes)
    val recognitionNeeded: RecognitionNeeded = RecognitionNeeded(passes, received, grant)
    val withhold: WithholdRecognition = WithholdRecognition(marked, received, passes)
    val forgetOtherEvents: ForgetOtherEvents = ForgetOtherEvents(recognitionRecords, passes)
    val importReady: ImportReady = ImportReady(received, grant)
    val holds: HoldsEverything = HoldsEverything(counts, union, services.deviceIdentity)
    val report: ReportReceivedPosition = ReportReceivedPosition(
        ReceivedReporting(backend.gateway, services.deviceIdentity),
        ReportedReceived(),
        services.wallClock,
        services.membership,
    )
    val stop: StopReceiving = StopReceiving(cleanup)
}
