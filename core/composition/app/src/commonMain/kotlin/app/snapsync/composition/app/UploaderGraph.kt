package app.snapsync.composition.app

import app.snapsync.application.upload.CreateJobs
import app.snapsync.application.upload.ReconcileUploader
import app.snapsync.application.upload.RecordFinishedUpload
import app.snapsync.application.upload.SettleStoredUploads
import app.snapsync.application.upload.StopUploads
import app.snapsync.composition.process.ProcessGraph
import app.snapsync.composition.process.UploadGraph
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.upload.AppUploaderAdmission
import app.snapsync.data.upload.DeviceListing
import app.snapsync.data.upload.ExtensionRegistrar
import app.snapsync.data.upload.FileUploads
import app.snapsync.data.upload.OsUploader
import app.snapsync.data.upload.SessionCreate
import app.snapsync.data.upload.SessionEnds
import app.snapsync.data.upload.SessionTier
import app.snapsync.data.upload.StagedDigests
import app.snapsync.data.upload.StagedFiles
import app.snapsync.data.upload.StagingSweep
import app.snapsync.data.upload.UploadExport
import app.snapsync.data.upload.UploadProgress
import app.snapsync.data.upload.UploadRowsReset
import app.snapsync.data.upload.UploadStandings
import app.snapsync.data.upload.UploaderSwitches
import app.snapsync.domain.upload.UploadHost

/**
 * **The app's own uploader** — the upload's layers over the app's services ([UploadGraph]), its transfer the app's own
 * session ([SessionTier]: a background `URLSession`, Android's uploads), and the use cases its entry points take.
 * Nothing here opens the shared database as it is built.
 */
class UploaderGraph(
    ports: AppPorts,
    process: ProcessGraph,
    services: ServicesGraph,
    backend: BackendGraph,
    ownPhotos: OwnPhotos,
    switches: UploaderSwitches,
    receiving: ReceivingGraph,
    album: AlbumGraph,
    osUploader: OsUploader,
    grant: GrantRead,
    wake: WakeGraph,
) {
    /** The app's admission: the grant's read plan — and the rig's switch, on in every shipped build. */
    val admission: AppUploaderAdmission = AppUploaderAdmission(ownPhotos, switches)

    /** The upload's layers over the app's own pieces — the identity that mints, the credential that attests. */
    val layers: UploadGraph = UploadGraph(
        records = services.sharedDatabase,
        membership = services.membership,
        identity = services.deviceIdentity,
        host = UploadHost.of(ports.process.build.uploadHost),
        photos = ownPhotos,
        admission = admission,
        // Echo suppression and recognition: the app's received photos, over its read-write store.
        received = receiving.received,
        placeOwn = album.placeOwn,
        manifestRecord = services.manifestRecord,
        clock = services.wallClock,
        // The token the app's credential keeps, the same copy every request it authenticates reads.
        credentials = services.credentials,
        credential = backend.attestation,
        exchange = backend.exchange,
        gateway = backend.gateway,
        keys = services.eventKeyStore,
        cipher = services.fileCipher,
        names = services.fileNameCipher,
    )

    private val staged: StagedFiles = StagedFiles(process.files)
    private val create: SessionCreate = SessionCreate(ports.uploadSession, services.mobileData, layers.record)
    private val fromFiles: FileUploads = FileUploads(
        staged,
        StagedDigests(process.files, process.crypto, staged),
        UploadExport(ports.gallery, process.files, layers.sealing, staged),
        UploadStandings(backend.gateway),
        create,
    )

    /** The app's own session as an upload tier. */
    val tier: SessionTier = SessionTier(ports.uploadSession, layers.requests, layers.sealing, create, fromFiles)

    /** Job creation through the app's own session. */
    val createJobs: CreateJobs = CreateJobs(layers.rows, tier, layers.resources, layers.walkRecord, layers.recent)

    /** How the session's transfers end, and what a leave cancels. */
    val ends: SessionEnds = SessionEnds(ports.uploadSession, staged, layers.record)

    /** The staged files no transfer outlived, swept at the foreground. */
    val sweep: StagingSweep = StagingSweep(process.files, staged, layers.rows, ports.uploadSession, fromFiles)

    val recordFinished: RecordFinishedUpload = RecordFinishedUpload(ends, services.membership)

    val settleStored: SettleStoredUploads = SettleStoredUploads(
        layers.rows,
        DeviceListing(backend.gateway, services.deviceIdentity, services.servedNames),
        layers.transitions,
    )

    /** The OS's registration of the upload extension, written through its disable→enable ritual. */
    val registrar: ExtensionRegistrar = ExtensionRegistrar(ports.extensionRegistry)

    val reconcile: ReconcileUploader = ReconcileUploader(services.membership, osUploader, grant, registrar)

    /** What a leave and a deleted event stop of the uploads. */
    val stop: StopUploads = StopUploads(registrar, wake.arming, ends)

    /** The join's reset of the upload rows, and the rig's reset of them. */
    val reset: UploadRowsReset = UploadRowsReset(services.sharedDatabase)

    /** How far this device's own photos have got — the status's read. */
    val progress: UploadProgress = UploadProgress(services.sharedDatabase)
}
