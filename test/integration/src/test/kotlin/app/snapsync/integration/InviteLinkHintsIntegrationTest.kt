package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.Layer
import app.snapsync.model.encodeEventUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * On a shipped build, no invite link, however it is crafted, joins, switches or starts sharing without the member
 * confirming on the join screen.
 *
 * The host starts as a rig build, which honours a link's hints; `device/invite-link-hints?honoured=false` plays the
 * shipped build, which ignores them. The positive control — a rig build auto-confirming the same link — is
 * `JoinGateIntegrationTest.autoJoin_auto_confirms_without_a_confirmation`, so the negatives here cannot pass by the
 * link simply failing to decode or load.
 */
@Verifies(
    spec = "join-event",
    requirement = "Joining happens only on confirmation and needs a connection",
    scenario = "A crafted link cannot skip the confirmation",
)
class InviteLinkHintsIntegrationTest {

    /** Every hint the decoder accepts, set to its most damaging value, on the event's whole invite. */
    private suspend fun Rig.craftedLink(eventId: String) = encodeEventUrl(
        EventLinkPayload(
            eventId,
            autoJoin = true,
            minPhotoDate = "2001-01-01T00:00:00Z",
            maxPhotoDate = "2099-01-01T00:00:00Z",
            direction = "upload",
            saveToAlbum = true,
            key = keyOf(eventId),
        ),
    )

    private suspend fun Rig.shippedBuild() {
        device("invite-link-hints", "honoured" to "false")
        device("relaunch")
    }

    @Test
    fun a_shipped_build_shows_the_join_screen_for_an_autoJoin_link_and_joins_nothing() = rigTest {
        val invited = registerEvent(name = "Anna's Wedding")
        shippedBuild()

        openLink(craftedLink(invited))

        val joining = awaitState { it.ui.layer is Layer.JoiningEvent }.ui.layer as Layer.JoiningEvent
        assertEquals(invited, joining.eventId, "the ordinary confirmation — the member decides")
        neverWithin(what = "the link joined without a tap") { it.ready.configResolved }
        assertNull(manifest(invited), "no enrolment without a tap")
    }

    @Test
    fun a_shipped_build_never_lets_an_autoJoin_link_leave_the_current_event() = rigTest {
        val invited = registerEvent(name = "Anna's Wedding")
        val current = createAndJoin(name = "Trip")
        shippedBuild()

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
