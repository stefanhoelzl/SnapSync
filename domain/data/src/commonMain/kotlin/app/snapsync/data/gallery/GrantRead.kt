package app.snapsync.data.gallery

import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.flow.StateFlow

/**
 * The photo-library grant as it stands now, in the domain's words — read on demand from the gallery port's grant, which
 * the platform re-reads at every return to the foreground (a change made in Settings reaches the app no other way).
 */
class GrantRead(private val grant: StateFlow<LibraryGrant>) {
    fun access(): PhotoAccess = grant.value.photoAccess()
}
