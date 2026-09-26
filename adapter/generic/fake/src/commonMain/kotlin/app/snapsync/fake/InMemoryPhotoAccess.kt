package app.snapsync.fake

import app.snapsync.model.GalleryAccess
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.PhotoAccessStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The honest in-memory photo-permission adapter: the status source and the Settings surface over one [status]
 * cell, as the iOS adapter serves both over one `PHPhotoLibrary` (capability `photo-access`). It is held to
 * `PhotoAccessContract` exactly as `PhotoLibraryPermission` is. Asking for access is the gallery's
 * ([inMemoryGallery], over the same cell).
 *
 * [openSettings] hands the user to another surface; off device there is none, so it changes nothing, and the
 * outcome a person would choose there reaches the [status] cell through its owner.
 */
internal class InMemoryPhotoAccess(
    private val status: MutableStateFlow<GalleryAccess>,
) : PhotoAccessStatusSource, PhotoAccessRequester {

    override val permission: StateFlow<GalleryAccess> = status.asStateFlow()

    override fun openSettings() = Unit
}
