package app.snapsync.compose

import app.snapsync.application.upload.ReconcileUploader
import app.snapsync.application.upload.RecordFinishedUpload
import app.snapsync.application.upload.SettleStoredUploads
import app.snapsync.application.upload.StopUploads
import app.snapsync.data.upload.AppUploaderAdmission
import app.snapsync.data.upload.DeviceListing
import app.snapsync.data.upload.ExtensionRegistrar
import app.snapsync.data.upload.FileUploads
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

/**
 * **The app's own uploader** — the upload's layers over the app's services ([UploadLayers]), its transfer the app's
 * own session ([SessionTier]: a background `URLSession`, Android's uploads), and the use cases its entry points take.
 * Built on first use: a background relaunch must not open the shared database at composition.
 */
internal class AppUploader(private val core: AppCore) {
    private val app get() = core.services
    private val ports get() = core.ports

    /** The app's admission: the grant's read plan — and the rig's switch, on in every shipped build. */
    val admission: AppUploaderAdmission by lazy { AppUploaderAdmission(core.ownPhotos, core.uploaderSwitches) }

    val services: UploadServices by lazy {
        UploadServices(
            records = app.sharedDatabase,
            membership = app.membership,
            config = app.config,
            mobileData = app.mobileData,
            deviceIdentity = app.deviceIdentity,
            host = ports.process.build.uploadHost,
            gallery = ports.gallery,
            photos = core.ownPhotos,
            admission = admission,
            manifestStore = app.manifestStore,
            manifestPublisher = core.backend.manifest,
            // Echo suppression and recognition: the app's received photos, over its read-write store.
            received = core.receiving.received,
            albumCoordinator = core.albumCoordinator,
            // The token the app's credential keeps, the same copy every request it authenticates reads.
            credentials = app.credentials,
            credential = core.attestation,
            exchange = core.exchange,
            gateway = core.backend.gateway,
            keys = app.eventKeyStore,
        )
    }

    val layers: UploadLayers by lazy { UploadLayers(core.process, services) }

    private val staged: StagedFiles by lazy { StagedFiles(core.process.files) }
    private val create: SessionCreate by lazy { SessionCreate(ports.uploadSession, app.mobileData, layers.record) }

    private val fromFiles: FileUploads by lazy {
        FileUploads(
            staged,
            StagedDigests(core.process.files, core.process.crypto, staged),
            UploadExport(ports.gallery, core.process.files, layers.sealing, staged),
            UploadStandings(core.backend.gateway, app.deviceIdentity),
            create,
        )
    }

    /** The app's own session as an upload tier. */
    val tier: SessionTier by lazy {
        SessionTier(
            ports.uploadSession,
            layers.requests,
            layers.sealing,
            create,
            fromFiles,
        )
    }

    /** How the session's transfers end, and what a leave cancels. */
    val ends: SessionEnds by lazy { SessionEnds(ports.uploadSession, staged, layers.record) }

    /** The staged files no transfer outlived, swept at the foreground. */
    val sweep: StagingSweep by lazy {
        StagingSweep(core.process.files, staged, layers.rows, ports.uploadSession, fromFiles)
    }

    val recordFinished: RecordFinishedUpload by lazy { RecordFinishedUpload(ends, app.membership) }

    val settleStored: SettleStoredUploads by lazy {
        SettleStoredUploads(layers.rows, DeviceListing(core.backend.gateway, app.deviceIdentity), layers.transitions)
    }

    private val registrar: ExtensionRegistrar by lazy { ExtensionRegistrar(ports.extensionRegistry) }

    val reconcile: ReconcileUploader by lazy {
        ReconcileUploader(app.membership, core.osUploader, core.grant, registrar)
    }

    fun stop(arming: app.snapsync.data.wake.WakeArming): StopUploads = StopUploads(registrar, arming, ends)

    /** The extension as the rig's switch now has it — deregistered at once when switched off (a rig build only). */
    suspend fun followSwitch() = registrar.followSwitch(core.uploaderSwitches.extension())

    /** The join's reset of the upload rows, and the rig's reset of them. */
    val reset: UploadRowsReset by lazy { UploadRowsReset(app.sharedDatabase) }

    /** How far this device's own photos have got — the status's read (4.9's). */
    val progress: UploadProgress by lazy { UploadProgress(app.sharedDatabase) }
}
