package app.snapsync.link

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.LinksContract
import app.snapsync.contracts.LinksState
import app.snapsync.contracts.LinksUnderTest
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import co.touchlab.kermit.Logger
import platform.Foundation.NSURL
import platform.Foundation.NSUserActivity
import platform.Foundation.NSUserActivityTypeBrowsingWeb
import kotlin.test.Test

/**
 * [IosLinks] against the [LinksContract] in the simulator's test executable: a Universal Link opened as iOS opens one —
 * a web-browsing user activity carrying the URL, handed to the entry the scene delegate's continuation calls.
 */
class IosLinksContractTest {

    private val binding = object : Binding<LinksState, LinksUnderTest> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = IosLinks::class
        override val reaches = setOf(LinksState.OPENABLE, LinksState.OPENED_WITH_NO_LINK, LinksState.OPENED_WITH_NO_URL)
        override fun create(state: LinksState, clauseId: String, log: CallLog): Entered<LinksUnderTest> {
            if (state !in reaches) return Entered.Unreachable("an install referrer is Google Play's: iOS hands the app none")
            val links = IosLinks(Logger.withTag("contract"))
            val hook = "continueUserActivity"
            return Entered.Ready(
                LinksUnderTest(
                    links.recorded(log),
                    open = { url ->
                        val activity = NSUserActivity(NSUserActivityTypeBrowsingWeb).apply { webpageURL = NSURL(string = url) }
                        links.deliverUserActivity(hook, activity)
                    },
                    // Handoff of another kind of activity, and a web-browsing one that carries no URL.
                    openNotALink = { links.deliverUserActivity(hook, NSUserActivity("app.snapsync.contract.handoff")) },
                    openWithoutUrl = { links.deliverUserActivity(hook, NSUserActivity(NSUserActivityTypeBrowsingWeb)) },
                ),
            )
        }
    }

    @Test
    fun `Universal Links satisfy the Links contract`() = verify(LinksContract, binding)
}
