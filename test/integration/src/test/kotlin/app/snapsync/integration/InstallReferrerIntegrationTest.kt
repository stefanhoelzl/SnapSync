package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.model.ConfigDecodeResult
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.decodeEventUrl
import app.snapsync.model.encodeEventUrl
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **An invite carried through Google Play** (`openspec/specs/invite-link`, "Without the app, the invite leads to a store
 * and back"), one scenario per test, over the real composed stack.
 *
 * The event page's Play button hands the invite to Play as the install referrer — the invite's payload (`v=3&d=…`),
 * and an encrypted event's key appended (`&k=…`) — and Play answers it to the installed app once, at its first start
 * ([Acts.installedFrom]). A referrer Play fills in itself, for an install that came through no invite's page, is
 * [ORGANIC]. What the app makes of either is its own: an invite opens its join screen, anything else opens nothing.
 */
class InstallReferrerIntegrationTest {

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Without the app, the invite leads to a store and back",
        scenario = "Installing from Google Play opens the join screen",
    )
    fun a_referrer_carrying_an_invite_opens_its_join_screen() = behaviour {
        val (event, referrer) = given {
            step("events are created plain", act = { devControls.encryptNewEvents(false) }, until = {})
            val event = registeredEvent()
            event to referrerOf(event)
        }

        whenever { installedFrom(referrer) }
        then { screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed } }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Without the app, the invite leads to a store and back",
        scenario = "Installing from Google Play opens an encrypted event's join screen",
    )
    fun a_referrer_carrying_an_encrypted_event_s_key_opens_its_join_screen_not_the_incomplete_invite() = behaviour {
        val (event, referrer) = given {
            val event = registeredEvent() // a rig build creates encrypted events
            event to referrerOf(event)
        }

        whenever { installedFrom(referrer) }
        then {
            screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed }
            neverScreen<Layer.JoiningEvent> { it.phase == JoinPhase.WrongLink }
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Without the app, the invite leads to a store and back",
        scenario = "An ordinary installation opens no join screen",
    )
    fun an_organic_referrer_opens_no_join_screen_and_reports_no_damaged_invite() = behaviour {
        given { inFront() }

        whenever { installedFrom(ORGANIC) }
        then {
            settled { true }
            neverScreen<Layer.JoiningEvent>()
            neverScreen<Layer.CreateEvent> { it.error != null }
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Without the app, the invite leads to a store and back",
        scenario = "Installing from a computer carries no invite",
    )
    fun an_install_from_a_computer_opens_nothing_and_the_invite_opened_on_the_phone_opens_its_join_screen() = behaviour {
        val (event, invite) = given {
            val event = registeredEvent()
            event to inviteFor(event)
        }

        // Play installs onto the phone what was chosen on a computer: the referrer is its own, and carries no invite.
        whenever { installedFrom(ORGANIC) }
        then {
            settled { true }
            neverScreen<Layer.JoiningEvent>()
        }

        whenever { openLink(invite) }
        then { screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed } }
    }

    /** The installation's first start, Play answering [referrer]. */
    private suspend fun Acts.installedFrom(referrer: String) = links.startInstalled(referrer)

    /**
     * What the page of [event]'s invite hands Play: the invite's payload, without its key, and an encrypted event's key
     * appended as `k` — exactly as the page builds it.
     */
    private suspend fun Given.referrerOf(event: String): String {
        val invite = inviteFor(event)
        val key = (decodeEventUrl(invite) as ConfigDecodeResult.Success).payload.key
        val payload = encodeEventUrl(EventLinkPayload(testEventId(event), autoJoin = false, minPhotoDate = null))
            .substringAfter('#')
        return key?.let { "$payload&k=$it" } ?: payload
    }

    private companion object {
        /** What Play answers an install that came through no invite's page. */
        const val ORGANIC = "utm_source=google-play&utm_medium=organic"
    }
}
