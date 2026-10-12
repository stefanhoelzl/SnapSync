package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** What the platform opened the app with: each case compared by what it carries, its hook included. */
class LinkDeliveryTest {

    @Test
    fun `each delivery compares by its hook and what it carries`() {
        val all = listOf(
            LinkDelivery.Link("onOpenURL", "https://x"),
            LinkDelivery.InstallReferrer("installReferrer", "v=3"),
            LinkDelivery.NotALink("onCreate", "android.intent.action.MAIN"),
            LinkDelivery.NoUrl("onLaunchActivity"),
        )
        assertEquals(
            all,
            listOf(
                LinkDelivery.Link("onOpenURL", "https://x"),
                LinkDelivery.InstallReferrer("installReferrer", "v=3"),
                LinkDelivery.NotALink("onCreate", "android.intent.action.MAIN"),
                LinkDelivery.NoUrl("onLaunchActivity"),
            ),
        )
        assertEquals(listOf("onOpenURL", "installReferrer", "onCreate", "onLaunchActivity"), all.map { it.hook })
        assertNotEquals<LinkDelivery>(
            LinkDelivery.NoUrl("onLaunchActivity"),
            LinkDelivery.NoUrl("onSceneContinueActivity"),
        )
    }
}
