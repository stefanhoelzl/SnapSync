package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.format.EventKeyText
import app.snapsync.format.InviteLinkText
import app.snapsync.format.LINK_ORIGIN
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import kotlin.test.Test
import kotlin.test.assertEquals

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
    private suspend fun Acts.craftedLink(eventId: String) =
        "$LINK_ORIGIN/join/$eventId#" + listOfNotNull(
            keyOf(eventId)?.let { "k=$it" },
            "autoJoin=true",
            "minPhotoDate=2001-01-01T00:00:00Z",
            "maxPhotoDate=2099-01-01T00:00:00Z",
            "direction=upload",
            "saveToAlbum=true",
        ).joinToString("&")

    /** [eventId]'s key as its invite carries it, or `null` for a plain event. */
    private suspend fun Acts.keyOf(eventId: String): String? =
        (InviteLinkText.parse(host.invite(eventId)) as InviteLinkVerdict.Invite).link.key?.let(EventKeyText::encode)

    @Test
    fun an_autoJoin_link_shows_the_join_screen_and_joins_nothing() = behaviour {
        val invited = given { registeredEvent(name = "Anna's Wedding") }

        lateinit var joining: Seen<Layer.JoiningEvent>
        whenever { openLink(craftedLink(invited)) }
        then {
            joining = screen<Layer.JoiningEvent>()
            neverScreen<Layer.Joined>() // the link joined without a tap
            never<WorldEvent.MemberJoined>() // no enrolment without a tap
        }
        assertEquals(invited, joining.value.eventId, "the ordinary confirmation — the member decides")
    }

    @Test
    fun an_autoJoin_link_never_leaves_the_current_event() = behaviour {
        val (invited, current) = given {
            val invited = registeredEvent(name = "Anna's Wedding")
            invited to joinedEvent()
        }

        lateinit var asked: Seen<Layer.Joined>
        whenever { openLink(craftedLink(invited)) }
        then {
            asked = screen<Layer.Joined> { it.pendingSwitch != null }
            never<WorldEvent.MemberLeft>() // the current event was not left
            never<WorldEvent.MemberJoined>() // no enrolment without a tap
            neverScreen<Layer.Joined> { it.membership.eventId != current }
        }
        assertEquals(
            invited,
            asked.value.pendingSwitch?.eventId,
            "the switch confirmation, exactly as for an unhinted invite",
        )
        assertEquals(current, asked.value.membership.eventId, "the current event was not left")
    }
}
