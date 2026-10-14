package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.presentation.url
import app.snapsync.ui.state.AppLink
import app.snapsync.ui.state.Layer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **The taps that hand the person to the platform's own UI** — the invite's share sheet, a link out of the app, the
 * app's Settings page, the limited-library picker: each reaches the platform with what the screen shows, and the app
 * keeps nothing of the hand-off. A device host cannot drive these (only a finger brings the person back); here the
 * platform's UI records what it was handed.
 *
 * And the upload extension's end of an invocation, which the operating system reports and the extension only records.
 */
class HandOffIntegrationTest {

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The joined screen offers the invite until the event's photos are deleted",
    )
    fun sharing_the_invite_hands_the_share_sheet_the_invite_the_screen_shows() = behaviour {
        lateinit var joined: Seen<Layer.Joined>
        given {
            createdEvent()
            step("joined", act = { user("confirmJoin") }, until = {
                joined = screen<Layer.Joined> { it.inviteUrl?.contains("#k=") == true }
            })
        }

        whenever { user("shareInvite") }
        then { event<WorldEvent.Shared> { it.text == joined.value.inviteUrl } }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "The app menu is one tap away on every screen",
        scenario = "Reading the Privacy Policy",
    )
    fun a_menu_link_opens_its_page_outside_the_app() = behaviour {
        whenever { user("openLink", "link" to AppLink.PRIVACY_POLICY.name) }
        then { event<WorldEvent.UrlOpened> { it.url == AppLink.PRIVACY_POLICY.url } }
    }

    @Test
    @Verifies(spec = "photo-access", requirement = "The system's access dialog is raised only by a deliberate tap")
    fun opening_settings_opens_the_app_s_settings_page_once() = behaviour {
        whenever { user("openSettings") }
        then { times(1) { event<WorldEvent.SettingsOpened>() } }
    }

    @Test
    fun choosing_more_photos_presents_the_platform_s_picker() = rigTest {
        permission(PhotoGrant.LIMITED)

        user("choosePhotos")

        assertEquals(1, awaitOs { it.pickersShown > 0 }.pickersShown)
    }

    @Test
    fun the_operating_system_ending_an_extension_invocation_is_recorded_as_an_ordinary_end() = rigTest {
        os("photokit-ext", "onTerminate")

        eventually(read = { client.logs() }) { logs: String -> "the OS ended this invocation" in logs }
    }
}
