package app.snapsync.android.permission

import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.flow.StateFlow

/**
 * What the gallery reads of the photo grant and asks of it — [AndroidPhotoPermission] on a phone. Its own type so a
 * device test can play a grant that changes while a read runs, which a test process cannot do to its own grant without
 * Android ending it.
 */
interface PhotoGrants {
    /** The grant as last read. */
    val grant: StateFlow<LibraryGrant>

    /** The grant as it stands now. Cheap, synchronous, never raises anything. */
    fun current(): LibraryGrant

    /** Ask for access while it was never decided, and answer the grant that results. */
    suspend fun requestAccess(): LibraryGrant

    /** The selection sheet, for a partial grant; answers the grant. */
    suspend fun widenSelection(): LibraryGrant
}
