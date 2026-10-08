package app.snapsync.android.link

import android.content.Intent
import android.net.Uri
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
import kotlin.test.Test

/**
 * [AndroidLinks] against the [LinksContract]: a link opened as the platform opens one — a `VIEW` intent carrying the
 * URI, handed to the entry the activity's single-top `onNewIntent` calls.
 */
class AndroidLinksContractTest {

    private val binding = object : Binding<LinksState, LinksUnderTest> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(LinksState.OPENABLE)
        override fun create(state: LinksState, clauseId: String, log: CallLog): Entered<LinksUnderTest> {
            val links = AndroidLinks(Logger.withTag("contract"))
            return Entered.Ready(
                LinksUnderTest(
                    links.recorded(log),
                ) { url -> links.deliverIntent("onNewIntent", Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            )
        }
    }

    @Test
    fun `VIEW intents satisfy the Links contract`() = verify(LinksContract, binding)
}
