package app.snapsync.data.gallery

import app.snapsync.data.sharing.BeforePublish
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.ports.Gallery
import kotlinx.coroutines.flow.Flow

/**
 * **The photo library's selection and grant, as the starts read them**: the selection observer the composition opens on
 * every start, its snapshots published through [SelectionSnapshots], and the grant read afresh from the platform.
 */
class LibrarySelection(private val gallery: Gallery) {
    /** Each snapshot the observer delivers, published into [snapshots] after [before] has seen it. */
    fun published(snapshots: SelectionSnapshots, before: BeforePublish): Flow<Unit> =
        snapshots.published(gallery.selection, before)

    /** Opens the selection observer: under a partial grant, its first snapshot is the start's baseline read. */
    fun open() = gallery.observeChanges(true)

    /** The grant now, read from the platform, as a read of the member's photos takes it. */
    fun access(): PhotoAccess = PhotoGrants.of(gallery.access()).access
}
