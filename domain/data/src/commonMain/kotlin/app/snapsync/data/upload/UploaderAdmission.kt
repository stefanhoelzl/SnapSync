package app.snapsync.data.upload

import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.domain.upload.UploadAdmission

/** Whether THIS process may create upload jobs now — each uploader states its own, read once per pass. */
interface UploaderAdmission {
    fun admission(): UploadAdmission
}

/** The app's: once the grant's read plan can read. */
class AppUploaderAdmission(private val photos: OwnPhotos) : UploaderAdmission {
    override fun admission(): UploadAdmission = UploadAdmission.forApp(photos.plan())
}

/** The upload extension's: only under a full grant, read at the pass. */
class ExtensionUploaderAdmission(private val grant: GrantRead) : UploaderAdmission {
    override fun admission(): UploadAdmission = UploadAdmission.forExtension(grant.access())
}
