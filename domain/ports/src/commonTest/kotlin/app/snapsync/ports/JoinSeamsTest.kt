package app.snapsync.ports

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import app.snapsync.model.JoinResult
import app.snapsync.model.Handoff

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
    fun the_platform_handoffs_default_to_inert() = runTest {
        // What every off-device composition (the harnesses, the world) stands on: there is no platform to
        // hand anything to, and saying so explicitly is what keeps the graph constructible there.
        val handoff = PlatformHandoff()
        assertSame(SharePresenter.None, handoff.share)
        assertSame(LinkOpener.None, handoff.links)

        // Inert means it answers — that nothing was handed off — not that it throws or is absent.
        assertTrue(handoff.share.share("https://example.invalid/join") is Handoff.Refused)
        assertTrue(handoff.links.open("https://example.invalid/app") is Handoff.Refused)
    }

    @Test
    fun a_supplied_handoff_is_the_one_used() = runTest {
        val opened = mutableListOf<String>()
        val shared = mutableListOf<String>()
        val handoff = PlatformHandoff(
            share = object : SharePresenter {
                override suspend fun share(text: String): Handoff = Handoff.Accepted.also { shared += text }
            },
            links = object : LinkOpener {
                override suspend fun open(url: String): Handoff = Handoff.Accepted.also { opened += url }
            },
        )

        handoff.share.share("invite")
        handoff.links.open("store")

        assertEquals(listOf("invite"), shared)
        assertEquals(listOf("store"), opened)
    }
}
