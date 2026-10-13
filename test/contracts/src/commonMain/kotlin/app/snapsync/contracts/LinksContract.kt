@file:OptIn(CompositionOnly::class)

package app.snapsync.contracts

import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.LinkDelivery
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.Links
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** What the app is opened with, as a binding can make the platform open it. */
enum class LinksState {
    /** An app the binding can open a web link into through its platform's own entry ([LinksUnderTest.open]). */
    OPENABLE,

    /** An app the platform opens with something that is not a link ([LinksUnderTest.openNotALink]). */
    OPENED_WITH_NO_LINK,

    /** An app the platform hands a link delivery that carries no URL ([LinksUnderTest.openWithoutUrl]). */
    OPENED_WITH_NO_URL,

    /** An installation that came with a referrer, read on each foreground start ([LinksUnderTest.startInstalled]). */
    INSTALLED_WITH_A_REFERRER,
}

/** The port, and the platform's own ways of opening the app — each `null` where the binding cannot pull it. */
class LinksUnderTest(
    val links: Links,
    val open: suspend (url: String) -> Unit,
    val openNotALink: (suspend () -> Unit)? = null,
    val openWithoutUrl: (suspend () -> Unit)? = null,
    /** One foreground start of an installation whose referrer the platform answers as [referrer]. */
    val startInstalled: (suspend (referrer: String) -> Unit)? = null,
)

/**
 * What the link entry promises (`docs/architecture.md`, "entry ports"): every way the platform opens the app reaches
 * the handler as what it is — a link raw, its fragment intact; something that is not a link, named; a delivery with
 * no URL; an installation's referrer, raw and once. Whether a link is an event link, and what opening one does, is
 * the core's.
 */
object LinksContract : Contract<LinksState, LinksUnderTest>("Links") {

    const val LINK = "https://snapsync.stho.net/join#v=3&d=contract-payload"

    const val REFERRER = "v=3&d=contract-payload"

    private suspend fun LinksUnderTest.deliveredBy(act: suspend () -> Unit): List<LinkDelivery> {
        val delivered = mutableListOf<LinkDelivery>()
        links.inbound(LinkHandlers(onLink = inboundOf { delivered += it }))
        act()
        awaitWithin { delivered.isNotEmpty() }
        return delivered
    }

    override val clauses = clauses {

        clause(
            "OPENABLE_A_LINK_ARRIVES_RAW",
            LinksState.OPENABLE,
            covers = cells {
                on<Links> {
                    answers(Links::inbound).returns()
                    calls(LinkHandlers::onLink, LinkDelivery.Link::class)
                }
            },
        ) { subject ->
            val link = assertIs<LinkDelivery.Link>(subject.deliveredBy { subject.open(LINK) }.single())
            assertEquals(LINK, link.url, "the link arrives whole, its fragment included")
        }

        clause(
            "OPENED_WITH_NO_LINK_IS_NAMED",
            LinksState.OPENED_WITH_NO_LINK,
            covers = cells { on<Links>().calls(LinkHandlers::onLink, LinkDelivery.NotALink::class) },
        ) { subject ->
            val open = assertNotNull(subject.openNotALink, "a binding that reaches this state opens the app so")
            assertIs<LinkDelivery.NotALink>(
                subject.deliveredBy(open).single(),
                "the app opened with something else says so, rather than staying silent",
            )
        }

        clause(
            "OPENED_WITH_NO_URL_SAYS_SO",
            LinksState.OPENED_WITH_NO_URL,
            covers = cells { on<Links>().calls(LinkHandlers::onLink, LinkDelivery.NoUrl::class) },
        ) { subject ->
            val open = assertNotNull(subject.openWithoutUrl, "a binding that reaches this state opens the app so")
            assertIs<LinkDelivery.NoUrl>(subject.deliveredBy(open).single())
        }

        clause(
            "INSTALLED_THE_REFERRER_ARRIVES_RAW_ONCE",
            LinksState.INSTALLED_WITH_A_REFERRER,
            covers = cells { on<Links>().calls(LinkHandlers::onLink, LinkDelivery.InstallReferrer::class) },
        ) { subject ->
            val start = assertNotNull(subject.startInstalled, "a binding that reaches this state starts installations")
            val delivered = subject.deliveredBy { start(REFERRER) }
            start(REFERRER)
            transferSettle()
            val referrer = assertIs<LinkDelivery.InstallReferrer>(delivered.single(), "a second start delivers nothing")
            assertEquals(REFERRER, referrer.referrer, "the referrer arrives as the platform answered it")
        }
    }
}
