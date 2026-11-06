package app.snapsync.data.links

import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.query.links.OpenedInvitesQuery
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The invite the app was opened with, until a screen opens it: a delivery waits for the screen that is not built yet, a
 * delivery that opens nothing changes nothing, and a newer invite offered while the screen opens the older one stays.
 */
class OpenedInvitesTest {
    private val invites = OpenedInvites()
    private val query = OpenedInvitesQuery(invites)

    private fun invite(id: String) = InviteLinkVerdict.Invite(InviteLink(testEventId(id), key = null))

    @Test
    fun `an invite waits until a screen opens it`() {
        val first = invite("11111111-1111-4111-8111-111111111111")
        invites.offer(first)
        assertSame(first, query.state.value)
        invites.opened(first)
        assertNull(query.state.value)
    }

    @Test
    fun `a newer invite offered meanwhile stays`() {
        val first = invite("11111111-1111-4111-8111-111111111111")
        invites.offer(first)
        invites.offer(InviteLinkVerdict.NotAnInvite)
        invites.opened(first)
        assertSame(InviteLinkVerdict.NotAnInvite, query.state.value)
    }
}
