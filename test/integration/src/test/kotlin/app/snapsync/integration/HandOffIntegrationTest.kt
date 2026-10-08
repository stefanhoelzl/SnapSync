package app.snapsync.integration

import app.snapsync.model.AppLink
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **The taps that hand the person to the platform's own UI** — the invite's share sheet, a link out of the app, the
 * app's Settings page, the limited-library picker (capabilities `join-event`, `photo-access`, `sync-status`): each
 * reaches the platform with what the screen shows, and the app keeps nothing of the hand-off. A device host cannot
 * drive these (only a finger brings the person back); here the platform's UI records what it was handed.
 *
 * And the upload extension's end of an invocation, which the operating system reports and the extension only records.
 */
class HandOffIntegrationTest {

    @Test
    fun sharing_the_invite_hands_the_share_sheet_the_invite_the_screen_shows() = rigTest {
        createAndJoin()
        val invite = awaitState { it.joined?.inviteUrl?.contains("#k=") == true }.joined!!.inviteUrl

        user("shareInvite")

        assertEquals(listOf(invite), awaitOs { it.shared.isNotEmpty() }.shared)
    }

    @Test
    fun a_menu_link_opens_its_page_outside_the_app() = rigTest {
        user("openLink", "link" to AppLink.PRIVACY_POLICY.name)

        assertEquals(listOf(AppLink.PRIVACY_POLICY.url), awaitOs { it.opened.isNotEmpty() }.opened)
    }

    @Test
    fun opening_settings_opens_the_app_s_settings_page_once() = rigTest {
        user("openSettings")

        assertEquals(1, awaitOs { it.settingsOpened > 0 }.settingsOpened)
    }

    @Test
    fun choosing_more_photos_presents_the_platform_s_picker() = rigTest {
        permission("LIMITED")

        user("choosePhotos")

        assertEquals(1, awaitOs { it.pickersShown > 0 }.pickersShown)
    }

    @Test
    fun the_operating_system_ending_an_extension_invocation_is_recorded_as_an_ordinary_end() = rigTest {
        os("photokit-ext", "onTerminate")

        eventually(read = { client.logs() }) { logs: String -> "the OS ended this invocation" in logs }
    }
}
