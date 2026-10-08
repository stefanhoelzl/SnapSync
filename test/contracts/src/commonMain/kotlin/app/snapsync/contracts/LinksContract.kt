package app.snapsync.contracts

import app.snapsync.model.LinkDelivery
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.Links
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Whether a link can be opened into the app. */
enum class LinksState {
    /** An app the binding can open a web link into through its platform's own entry ([LinksUnderTest.open]). */
    OPENABLE,
}

/** The port, and an opened link as the platform delivers it. */
class LinksUnderTest(val links: Links, val open: suspend (url: String) -> Unit)

/**
 * What the link entry promises (`docs/architecture.md`, "entry ports"): a link opened into the app reaches the handler
 * raw — the fragment an event link carries its payload in intact — and marked a web link. Whether it is an event link,
 * and what opening one does, is the core's.
 */
object LinksContract : Contract<LinksState, LinksUnderTest>("Links") {

    const val LINK = "https://snapsync.stho.net/join#v=3&d=contract-payload"

    override val clauses = clauses {

        clause(
            "OPENABLE_A_LINK_ARRIVES_RAW",
            LinksState.OPENABLE,
            covers = cells {
                on<Links> {
                    answers(Links::listen).returns()
                    calls(LinkHandlers::onLink, LinkDelivery::class)
                }
            },
        ) { subject ->
            val delivered = mutableListOf<LinkDelivery>()
            subject.links.listen(LinkHandlers(onLink = { delivered += it }))
            subject.open(LINK)
            awaitWithin { delivered.isNotEmpty() }
            val link = delivered.single()
            assertEquals(LINK, link.url, "the link arrives whole, its fragment included")
            assertTrue(link.isWebLink, "and as the web link it is")
        }
    }
}
