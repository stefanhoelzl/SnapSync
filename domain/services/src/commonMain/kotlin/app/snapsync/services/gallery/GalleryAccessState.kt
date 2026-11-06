package app.snapsync.services.gallery

import app.snapsync.model.GalleryAccess
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile

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

    /**
     * What the grant does after the launch, as real changes only: the value it holds now — what the launch reconciles
     * against — is captured here, at the call, and dropped, so a change that lands while the launch reconciles is still
     * delivered, after it.
     */
    fun changesSinceLaunch(): Flow<GalleryAccess> {
        val atLaunch = grant.value
        return grant.dropWhile { it == atLaunch }
    }
}
