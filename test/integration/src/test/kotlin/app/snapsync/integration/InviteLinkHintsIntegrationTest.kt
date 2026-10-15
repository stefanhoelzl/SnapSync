package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.format.LINK_ORIGIN
import app.snapsync.ui.state.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * No invite link, however it is crafted, joins, switches or starts sharing without the member confirming on the join
 * screen. Links once carried development hints (`autoJoin` and its overrides); one that still does opens the ordinary
 * confirmation — which is also what shows the link decoded and loaded, so the negatives cannot pass by the link
 * simply failing.
 */
@Verifies(
    spec = "join-event",
    requirement = "Joining happens only on confirmation and needs a connection",
    scenario = "A crafted link cannot skip the confirmation",
)
class InviteLinkHintsIntegrationTest {

    /** Every hint links once carried, set to its most damaging value, on the event's whole invite. */
    private suspend fun Rig.craftedLink(eventId: String) =
        "$LINK_ORIGIN/join/$eventId#" + listOfNotNull(
            keyOf(eventId)?.let { "k=$it" },
            "autoJoin=true",
            "minPhotoDate=2001-01-01T00:00:00Z",
            "maxPhotoDate=2099-01-01T00:00:00Z",
            "direction=upload",
            "saveToAlbum=true",
        ).joinToString("&")

    @Test
    fun an_autoJoin_link_shows_the_join_screen_and_joins_nothing() = rigTest {
        val invited = registerEvent(name = "Anna's Wedding")

        openLink(craftedLink(invited))

        val joining = awaitState { it.ui.layer is Layer.JoiningEvent }.ui.layer as Layer.JoiningEvent
        assertEquals(invited, joining.eventId, "the ordinary confirmation — the member decides")
        neverWithin(what = "the link joined without a tap") { it.ready.configResolved }
        assertNull(manifest(invited), "no enrolment without a tap")
    }

    @Test
    fun an_autoJoin_link_never_leaves_the_current_event() = rigTest {
        val invited = registerEvent(name = "Anna's Wedding")
        val current = createAndJoin(name = "Trip")

        openLink(craftedLink(invited))

        val joined = awaitState { it.joined?.pendingSwitch != null }.joined!!
        assertEquals(
            invited,
            joined.pendingSwitch?.eventId,
            "the switch confirmation, exactly as for an unhinted invite",
        )
        assertEquals(current, joined.membership.eventId, "the current event was not left")
        assertFalse(state().ready.eventId == invited)
        assertNull(manifest(invited), "no enrolment without a tap")
    }
}
