package app.snapsync.contract

import app.snapsync.gallery.IosGallery
import app.snapsync.gallery.IosGalleryReader
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.ports.Gallery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app's real gallery, as the photo-access bindings hand it to a clause: the grant is what it reports. */
fun photoAccessGallery(): Gallery =
    IosGallery(IosGalleryReader(), PhotoLibraryPermission(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
