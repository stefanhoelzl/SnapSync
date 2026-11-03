package app.snapsync.mock

import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.upload.OsUploader
import app.snapsync.data.upload.UploaderSwitches
import app.snapsync.model.RegistrationAnswer
import app.snapsync.ports.DevControls
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.RegistrationState
import app.snapsync.ports.UploaderPin
import kotlinx.coroutines.flow.StateFlow

/**
 * The OS's own uploader as a test plays it: an OS that [supported] carries the extension or not, the [grant] as the
 * gallery publishes it, what the OS reads of the registration ([registered]) and the rig's switch ([pin]) — each read
 * at every ask, so a test that changes one governs the next answer.
 */
fun osUploaderOver(
    supported: Boolean,
    grant: StateFlow<LibraryGrant>,
    registered: () -> RegistrationState = { RegistrationState.NOT_REGISTERED },
    pin: () -> UploaderPin? = { null },
): OsUploader = OsUploader(
    object : ExtensionRegistry {
        override val supported: Boolean = supported

        override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer = RegistrationAnswer.Unsupported

        override fun isEnabled(): RegistrationState = registered()
    },
    UploaderSwitches(
        object : DevControls {
            override fun uploaderPin(): UploaderPin? = pin()

            override fun createsPlainEvents(): Boolean = false
        },
    ),
    GrantRead(grant),
)
