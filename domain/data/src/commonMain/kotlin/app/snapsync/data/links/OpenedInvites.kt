package app.snapsync.data.links

import app.snapsync.domain.invite.InviteLinkVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The invite the app was last opened with, read, until a screen has opened it: a delivery the platform made — cold at
 * launch or warm — waits here for a screen that is not built yet, instead of the delivery waiting for the screen.
 */
class OpenedInvites {
    private val latest = MutableStateFlow<InviteLinkVerdict?>(null)

    /** The invite waiting to be opened, or `null`. */
    val pending: StateFlow<InviteLinkVerdict?> get() = latest

    /** The app was opened with [invite]. */
    fun offer(invite: InviteLinkVerdict) {
        latest.value = invite
    }

    /** A screen opened [invite]: it no longer waits. A newer one offered meanwhile stays. */
    fun opened(invite: InviteLinkVerdict) {
        latest.compareAndSet(invite, null)
    }
}
