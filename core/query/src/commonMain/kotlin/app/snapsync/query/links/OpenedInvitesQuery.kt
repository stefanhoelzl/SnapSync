package app.snapsync.query.links

import app.snapsync.data.links.OpenedInvites
import app.snapsync.domain.invite.InviteLinkVerdict
import kotlinx.coroutines.flow.StateFlow

/** The invite the app was opened with, read, until a screen has opened it — `null` while none waits. */
class OpenedInvitesQuery(invites: OpenedInvites) {
    val state: StateFlow<InviteLinkVerdict?> = invites.pending
}
