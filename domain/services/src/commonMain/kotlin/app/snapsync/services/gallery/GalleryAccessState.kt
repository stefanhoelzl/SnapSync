package app.snapsync.services.gallery

import app.snapsync.model.GalleryAccess
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.flow.StateFlow

/**
 * **What the photo-library grant means right now**, observed: the one place the core reads
 * the grant, over the permission port. A feature asks this what the grant is, rather than comparing grant values
 * of its own.
 *
 * The grant itself is the platform's and arrives through the port: the platform re-reads it at every return to the
 * foreground, since a change made in Settings reaches the app no other way.
 */
class GalleryAccessState(source: StateFlow<LibraryGrant>) {

    /** The grant, level-triggered: its current value is always available synchronously. */
    val grant: StateFlow<GalleryAccess> = GalleryAccessFlow(source)
}
