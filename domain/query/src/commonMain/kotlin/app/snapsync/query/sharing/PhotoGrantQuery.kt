package app.snapsync.query.sharing

import app.snapsync.data.gallery.PhotoGrants
import app.snapsync.domain.sharing.PhotoGrant
import kotlinx.coroutines.flow.StateFlow

/** The photo-library grant, as the screen follows it. */
class PhotoGrantQuery(grants: PhotoGrants) {
    val state: StateFlow<PhotoGrant> = grants.state
}
