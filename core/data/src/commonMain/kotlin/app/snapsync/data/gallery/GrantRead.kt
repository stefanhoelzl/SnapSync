package app.snapsync.data.gallery

import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.upload.FullGrant
import app.snapsync.domain.upload.MintsFullGrant
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.flow.StateFlow

/**
 * The photo-library grant as it stands now, in the domain's words — read on demand from the gallery port's grant, which
 * the platform re-reads at every return to the foreground (a change made in Settings reaches the app no other way).
 */
class GrantRead(private val grant: StateFlow<LibraryGrant>) {
    /** The grant now. */
    fun grant(): PhotoGrant = PhotoGrants.of(grant.value)

    /** The grant now, as a read of the member's photos takes it. */
    fun access(): PhotoAccess = grant().access

    /** Whether the grant is full right now — the one mint of [FullGrant], read afresh at each ask. */
    @OptIn(MintsFullGrant::class)
    fun full(): FullGrantRead = when (access()) {
        PhotoAccess.FULL -> FullGrantRead.Full(FullGrant())
        PhotoAccess.LIMITED, PhotoAccess.NONE -> FullGrantRead.NotFull
    }
}

/** Whether the member's photo grant is full right now. */
sealed interface FullGrantRead {
    class Full(val grant: FullGrant) : FullGrantRead

    /** A partial grant, or none: the upload extension may not be registered. */
    data object NotFull : FullGrantRead
}
