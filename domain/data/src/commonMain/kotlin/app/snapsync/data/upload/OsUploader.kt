package app.snapsync.data.upload

import app.snapsync.data.gallery.GrantRead
import app.snapsync.domain.upload.OsRegistration
import app.snapsync.domain.upload.OsSupport
import app.snapsync.domain.upload.OsUploaderAdmission
import app.snapsync.domain.upload.OsUploaderStanding
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.RegistrationState

/**
 * The operating system's own uploader — the upload extension — as the app asks about it: whether it may be registered
 * now ([admission]), and whether the OS confirms it is ([standing]). Both are read fresh at every call: the grant
 * changes at runtime.
 */
class OsUploader(
    private val registry: ExtensionRegistry,
    private val switches: UploaderSwitches,
    private val grant: GrantRead,
) {
    /** Whether the extension may be registered now. Asks the OS nothing. */
    fun admission(): OsUploaderAdmission = OsUploaderAdmission.of(support(), grant.access(), switches.extension())

    /** Whether the OS confirms the extension is registered — asked only while it may be. */
    fun standing(): OsUploaderStanding = when (val admission = admission()) {
        OsUploaderAdmission.REGISTRABLE -> OsUploaderStanding.of(admission, registration())
        OsUploaderAdmission.NOT_REGISTRABLE -> OsUploaderStanding.of(admission, OsRegistration.NOT_ASKED)
    }

    private fun support(): OsSupport = when (registry.supported) {
        true -> OsSupport.CARRIED
        false -> OsSupport.NOT_CARRIED
    }

    private fun registration(): OsRegistration = when (registry.isEnabled()) {
        RegistrationState.REGISTERED -> OsRegistration.REGISTERED
        RegistrationState.NOT_REGISTERED -> OsRegistration.NOT_REGISTERED
        RegistrationState.UNSUPPORTED -> OsRegistration.UNSUPPORTED
    }
}
