package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **An invite whose key was cut short never joins its encrypted event**: the key part still reads as text, but as
 * fewer bytes than an event's key has, so it is no key at all — the link is wrong, and nothing is joined.
 */
class CutKeyInviteIntegrationTest {

    @Test
    @Verifies(spec = "invite-link", requirement = "An incomplete invite never joins an encrypted event")
    fun an_invite_whose_key_is_cut_short_is_a_wrong_link_and_joins_nothing() = behaviour {
        val invite = given { inviteFor(registeredEvent()) }

        whenever { openLink(invite.substringBefore(KEY) + KEY + invite.substringAfter(KEY).take(THIRTY_BYTES)) }
        then {
            screen<Layer.JoiningEvent> { it.phase == JoinPhase.WrongLink }
            never<WorldEvent.MemberJoined>()
            settled { true }
        }
    }

    private companion object {
        /** Where an invite's key starts. */
        const val KEY = "#k="

        /** Forty base64url characters: thirty bytes, two short of an event's key. */
        const val THIRTY_BYTES = 40
    }
}
