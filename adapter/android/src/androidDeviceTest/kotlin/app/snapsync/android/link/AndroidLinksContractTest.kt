package app.snapsync.android.link

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.snapsync.android.storage.context
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
 * [AndroidLinks] against the [LinksContract]: the app opened as the platform opens it — a `VIEW` intent carrying the
 * URI, the launcher's `MAIN`, a `VIEW` with no URI — handed to the entry the activity's single-top `onNewIntent` calls,
 * and an installation's referrer read by the real [AndroidInstallReferrer] over its real record and a scripted Play.
 */
class AndroidLinksContractTest {

    private val binding = object : Binding<LinksState, LinksUnderTest> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidLinks::class
        override val reaches = setOf(
            LinksState.OPENABLE,
            LinksState.OPENED_WITH_NO_LINK,
            LinksState.OPENED_WITH_NO_URL,
            LinksState.INSTALLED_WITH_A_REFERRER,
        )

        override fun create(state: LinksState, clauseId: String, log: CallLog): Entered<LinksUnderTest> {
            val links = AndroidLinks(Logger.withTag("contract"))
            val record = context.getSharedPreferences("install-referrer-contract-$clauseId", Context.MODE_PRIVATE)
            record.edit().clear().commit()
            return Entered.Ready(
                LinksUnderTest(
                    links.recorded(log),
                    open = { url -> links.deliverIntent("onNewIntent", Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                    openNotALink = { links.deliverIntent("onNewIntent", Intent(Intent.ACTION_MAIN)) },
                    openWithoutUrl = { links.deliverIntent("onNewIntent", Intent(Intent.ACTION_VIEW)) },
                    startInstalled = { referrer ->
                        val play = ReferrerSource { answer -> answer(ReferrerAnswer.Referrer(referrer)) }
                        AndroidInstallReferrer(record, play, links, Logger.withTag("contract")).deliverOnce()
                    },
                ),
            ) { record.edit().clear().commit() }
        }
    }

    @Test
    fun `the platform ways of opening the app satisfy the Links contract`() = verify(LinksContract, binding)
}
