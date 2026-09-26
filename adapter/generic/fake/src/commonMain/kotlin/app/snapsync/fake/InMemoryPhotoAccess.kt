package app.snapsync.fake

import app.snapsync.model.GalleryAccess
import app.snapsync.ports.PhotoAccessStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The honest in-memory photo-permission status over one [status] cell, as the iOS adapter serves it over one
 * `PHPhotoLibrary` (capability `photo-access`). It is held to `PhotoAccessContract` exactly as
 * `PhotoLibraryPermission` is. Asking for access is the gallery's ([inMemoryGallery], over the same cell); opening
 * the Settings page is `SystemUi`'s, which off device reaches nothing — the outcome a person would choose there
 * reaches the [status] cell through its owner.
 */
internal class InMemoryPhotoAccess(
    private val status: MutableStateFlow<GalleryAccess>,
) : PhotoAccessStatusSource {

    override val permission: StateFlow<GalleryAccess> = status.asStateFlow()
}
