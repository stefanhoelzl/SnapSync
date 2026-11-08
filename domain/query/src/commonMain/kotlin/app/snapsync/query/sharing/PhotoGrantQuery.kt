package app.snapsync.query.sharing

import app.snapsync.data.gallery.PhotoGrants
import app.snapsync.domain.sharing.PhotoGrant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile

/**
 * The photo-library grant, as the core follows it. The grant is the platform's and arrives through the gallery port,
 * which re-reads it at every return to the foreground, since a change made in Settings reaches the app no other way.
 */
class PhotoGrantQuery(grants: PhotoGrants) {
    /** The grant, level-triggered: its current value is always available synchronously. */
    val state: StateFlow<PhotoGrant> = grants.state

    /**
     * What the grant does after the launch, as real changes only: the value it holds now — what the launch reconciles
     * against — is captured here, at the call, and dropped, so a change that lands while the launch reconciles is still
     * delivered, after it.
     */
    fun changesSinceLaunch(): Flow<PhotoGrant> {
        val atLaunch = state.value
        return state.dropWhile { it == atLaunch }
    }
}
