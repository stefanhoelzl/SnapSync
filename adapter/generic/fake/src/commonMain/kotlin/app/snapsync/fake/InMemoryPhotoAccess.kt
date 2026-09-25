package app.snapsync.fake

import app.snapsync.model.PermissionStatus
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.PhotoAccessStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The honest in-memory photo-access adapter: both permission ports over one [status] cell, as the iOS
 * adapter implements both over one `PHPhotoLibrary` (capability `photo-access`). It is held to
 * `PhotoAccessContract` exactly as `PhotoLibraryPermission` is.
 *
 * [answer] is what the user will choose when asked, which is state the platform holds and this app cannot
 * see: [request] applies it **only while the grant is undetermined**, because a platform asks once and
 * afterwards a request changes nothing. [openSettings] and [choosePhotos] hand the user to another surface;
 * off device there is none, so they change nothing, and the outcome a person would choose there reaches the
 * [status] cell through its owner.
 */
internal class InMemoryPhotoAccess(
    private val status: MutableStateFlow<PermissionStatus>,
    private val answer: PermissionStatus = PermissionStatus.GRANTED,
) : PhotoAccessStatusSource, PhotoAccessRequester {

    override val permission: StateFlow<PermissionStatus> = status.asStateFlow()

    override fun request() {
        if (status.value == PermissionStatus.NOT_DETERMINED) status.value = answer
    }

    override fun openSettings() = Unit

    override fun choosePhotos() = Unit
}
