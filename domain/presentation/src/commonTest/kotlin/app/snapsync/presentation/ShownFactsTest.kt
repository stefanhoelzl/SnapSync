package app.snapsync.presentation

import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.request.OwnPage
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.presentation.JoinedLayer.message
import app.snapsync.presentation.UnjoinedLayer.message
import app.snapsync.ui.state.AppLink
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.ScreenMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The facts presentation hands the screen rather than letting it decide: what a refusal says, what the network notice
 * says, and where the app menu's links point.
 */
class ShownFactsTest {

    @Test
    fun `each refusal has its own message`() {
        assertEquals(
            listOf(ScreenMessage.DEVICE_MODIFIED, ScreenMessage.DEVICE_UNVERIFIABLE, ScreenMessage.APP_NOT_GENUINE),
            DeviceRefusal.entries.map { it.message },
        )
    }

    @Test
    fun `the network notice says nothing while online restricted or not`() {
        assertNull(NetworkAccess.Online(restricted = false).notice)
        assertNull(NetworkAccess.Online(restricted = true).notice)
        assertEquals(NetworkNotice.OFFLINE, NetworkAccess.Offline.notice)
        assertEquals(NetworkNotice.BLOCKED, NetworkAccess.Blocked.notice)
    }

    @Test
    fun `the links are the app's own site's pages`() {
        assertEquals(OwnPage.WEBSITE, AppLink.WEBSITE.page)
        assertEquals(OwnPage.PRIVACY_POLICY, AppLink.PRIVACY_POLICY.page)
    }
}
