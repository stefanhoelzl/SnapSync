package app.snapsync.data.upload

import app.snapsync.data.gallery.GalleryGrantRead
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.domain.upload.AppUploaderSwitch
import app.snapsync.domain.upload.UploadAdmission
import app.snapsync.ports.GalleryReader

/** Whether THIS process may create upload jobs now — each uploader states its own, read once per pass. */
interface UploaderAdmission {
    fun admission(): UploadAdmission
}

/** The app's: once the grant's read plan can read — and while the rig's switch (on in every shipped build) is on. */
class AppUploaderAdmission(private val photos: OwnPhotos, private val switches: UploaderSwitches) : UploaderAdmission {
    override fun admission(): UploadAdmission = when (switches.app()) {
        AppUploaderSwitch.ON -> UploadAdmission.forApp(photos.plan())
        AppUploaderSwitch.OFF -> UploadAdmission.WITHHELD
    }
}

/** The upload extension's: only under a full grant, read from its own gallery at the pass. */
class ExtensionUploaderAdmission(gallery: GalleryReader) : UploaderAdmission {
    private val grant = GalleryGrantRead(gallery)

    override fun admission(): UploadAdmission = UploadAdmission.forExtension(grant.access())
}
