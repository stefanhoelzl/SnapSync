package app.snapsync.android.link

import android.content.Context
import android.content.SharedPreferences
import app.snapsync.android.storage.context
import app.snapsync.model.EncryptedFileFormat
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.LINK_ORIGIN
import app.snapsync.model.LinkDelivery
import app.snapsync.model.encodeEventKey
import app.snapsync.model.encodeEventUrl
import app.snapsync.ports.LinkHandlers
import co.touchlab.kermit.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The install-referrer reader's once-per-install bookkeeping (capability `join-event`), over a scripted Play answer and
 * the REAL `SharedPreferences` it records in. What the real Play client answers on a Play-installed build is not
 * reachable before the listing is public; the seam leaves only that library's own behaviour untested.
 */
class AndroidInstallReferrerTest {
    private val invite = encodeEventUrl(EventLinkPayload(eventId = "11111111-1111-4111-8111-111111111111"))
    private val delivered = mutableListOf<LinkDelivery>()
    private val links = AndroidLinks(
        Logger.withTag("test"),
    ).apply { listen(LinkHandlers(onLink = { delivered += it })) }
    private lateinit var record: SharedPreferences

    @BeforeTest
    fun clearRecord() {
        record = context.getSharedPreferences("install-referrer-test", Context.MODE_PRIVATE)
        record.edit().clear().commit()
    }

    @AfterTest
    fun dropRecord() {
        record.edit().clear().commit()
    }

    /** A fresh reader — one process start — over the same record, answering [answer] and counting the asks. */
    private fun start(answer: ReferrerAnswer, asks: MutableList<Unit> = mutableListOf()) =
        AndroidInstallReferrer(record, {
            asks += Unit
            it(answer)
        }, links, Logger.withTag("test")).deliverOnce()

    @Test
    fun `an invite is delivered as its event link once`() {
        start(ReferrerAnswer.Referrer(invite.substringAfter('#')))
        assertEquals(listOf(invite), delivered.map { it.url })
        assertTrue(delivered.single().isWebLink, "it opens exactly as a tapped web link")

        val asks = mutableListOf<Unit>()
        start(ReferrerAnswer.Referrer(invite.substringAfter('#')), asks)
        assertEquals(
            1,
            delivered.size,
            "Play answers the same referrer for 90 days — the second start delivers nothing",
        )
        assertTrue(asks.isEmpty(), "a handled installation does not even ask")
    }

    @Test
    fun `an encrypted events referrer is delivered as its whole invite`() {
        val key = encodeEventKey(ByteArray(EncryptedFileFormat.KEY_LENGTH))
        start(ReferrerAnswer.Referrer(invite.substringAfter('#') + "&k=$key"))
        assertEquals(listOf("$LINK_ORIGIN/join/11111111-1111-4111-8111-111111111111#k=$key"), delivered.map { it.url })
    }

    @Test
    fun `an organic install is recorded and delivers nothing`() {
        start(ReferrerAnswer.Referrer("utm_source=google-play&utm_medium=organic"))
        assertTrue(delivered.isEmpty())
        val asks = mutableListOf<Unit>()
        start(ReferrerAnswer.Referrer(invite.substringAfter('#')), asks)
        assertTrue(asks.isEmpty() && delivered.isEmpty(), "the installation's referrer was handled")
    }

    @Test
    fun `a device without Play is recorded and never asked again`() {
        start(ReferrerAnswer.NeverAvailable)
        val asks = mutableListOf<Unit>()
        start(ReferrerAnswer.Referrer(invite.substringAfter('#')), asks)
        assertTrue(asks.isEmpty() && delivered.isEmpty())
    }

    @Test
    fun `a busy Play is asked again on the next start`() {
        start(ReferrerAnswer.TryLater)
        assertTrue(delivered.isEmpty())
        start(ReferrerAnswer.Referrer(invite.substringAfter('#')))
        assertEquals(listOf(invite), delivered.map { it.url })
    }
}
