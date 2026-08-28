package app.snapsync.ports

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The vocabulary the join split introduced, and the inert defaults the off-device compositions stand on.
 *
 * Enumerating [JoinResult] looks like testing the language until you ask what it is FOR: the whole point
 * of the split is that "the event is full" and "the network blipped" stop being the same answer, so the
 * set of answers is the contract. A member silently added or removed here changes what the join surface
 * can say (capability `join-event`).
 */
class JoinSeamsTest {

    @Test
    fun a_join_has_exactly_four_answers() {
        assertEquals(
            listOf("JOINED", "EVENT_FULL", "EVENT_NOT_FOUND", "FAILED"),
            JoinResult.entries.map { it.name },
            "the join surface renders one screen per answer; adding one silently leaves it unrendered",
        )
    }

    @Test
    fun a_shape_failure_is_a_distinguishable_type_carrying_its_reason() {
        // The reconciler branches on this type to tell a permanent failure from a transient one, so it
        // must stay a type rather than a message anyone has to parse.
        val e = DeviceListingShapeException("no assetId")
        assertTrue(e is Exception)
        assertEquals("no assetId", e.message)
    }

    @Test
    fun the_platform_handoffs_default_to_inert() = runTest {
        // What every off-device composition (the harnesses, the world) stands on: there is no platform to
        // hand anything to, and saying so explicitly is what keeps the graph constructible there.
        val handoff = PlatformHandoff()
        assertSame(SharePresenter.None, handoff.share)
        assertSame(LinkOpener.None, handoff.links)

        // Inert means it returns, not that it throws or is absent.
        handoff.share.share("https://example.invalid/join")
        handoff.links.open("https://example.invalid/app")
    }

    @Test
    fun a_supplied_handoff_is_the_one_used() {
        val opened = mutableListOf<String>()
        val shared = mutableListOf<String>()
        val handoff = PlatformHandoff(
            share = object : SharePresenter { override fun share(text: String) { shared += text } },
            links = object : LinkOpener { override fun open(url: String) { opened += url } },
        )

        handoff.share.share("invite")
        handoff.links.open("store")

        assertEquals(listOf("invite"), shared)
        assertEquals(listOf("store"), opened)
    }
}
