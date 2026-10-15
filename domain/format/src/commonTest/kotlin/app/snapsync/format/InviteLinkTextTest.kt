package app.snapsync.format

import app.snapsync.domain.event.EventId
import app.snapsync.domain.invite.InviteKey
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An invite link's text (capability `join-event`): the one codec, both forms, never a throw. */
class InviteLinkTextTest {

    private val id = "11111111-1111-4111-8111-111111111111"
    private val event = EventId.parse(id)!!
    private val plain = InviteLink(event, null)
    private val keyText = EventKeyText.encode(ByteArray(EventKeyText.KEY_BYTES) { it.toByte() })
    private val keyed = InviteLink(event, InviteKey(keyText))

    private fun absent(json: String) = Base64.UrlSafe.withPadding(
        Base64.PaddingOption.ABSENT,
    ).encode(json.encodeToByteArray())

    private fun invite(raw: String): InviteLink = assertIs<InviteLinkVerdict.Invite>(
        InviteLinkText.parse(raw),
        raw,
    ).link

    private fun fragmentOf(link: String) = link.substringAfter('#')

    // ---- the fragment form ----

    @Test
    fun `a plain event's invite is the fragment form every build reads, and reads back`() {
        val text = InviteLinkText.format(plain)
        assertTrue(text.startsWith("$LINK_ORIGIN/join#v=3&d="), text)
        assertEquals(plain, invite(text))
        assertEquals(plain, invite("  $text\n"))
    }

    @Test
    fun `a padded payload and an uppercase id read too`() {
        val padded = Base64.UrlSafe.withPadding(
            Base64.PaddingOption.PRESENT,
        ).encode("""{"eventId":"$id"}""".encodeToByteArray())
        assertEquals(plain, invite("$LINK_ORIGIN/join#v=3&d=$padded"))
        assertEquals(
            plain.event,
            invite("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"${id.uppercase()}"}""")}").event,
        )
    }

    @Test
    fun `an older link's hints are read and ignored`() {
        val hinted = """{"eventId":"$id","autoJoin":true,"minPhotoDate":"2026-07-01T00:00:00Z",""" +
            """"maxPhotoDate":"2026-07-02T00:00:00Z","direction":"upload","saveToAlbum":false}"""
        assertEquals(plain, invite("$LINK_ORIGIN/join#v=3&d=${absent(hinted)}"))
    }

    @Test
    fun `a fragment form that names no event is no invite`() {
        for (raw in listOf(
            "snapsync://config?v=3&d=${absent("""{"eventId":"$id"}""")}",
            "https://elsewhere.example/join#v=3&d=${absent("""{"eventId":"$id"}""")}",
            "$LINK_ORIGIN/other#v=3&d=${absent("""{"eventId":"$id"}""")}",
            "$LINK_ORIGIN/join#v=2&d=${absent("""{"eventId":"$id"}""")}",
            "$LINK_ORIGIN/join#d=${absent("""{"eventId":"$id"}""")}",
            "$LINK_ORIGIN/join#v=3",
            "$LINK_ORIGIN/join#v=3&d=not-base64!",
            "$LINK_ORIGIN/join#v=3&d=${absent("not json")}",
            "$LINK_ORIGIN/join#v=3&d=${absent("{}")}",
            "$LINK_ORIGIN/join#v=3&d=${absent("[]")}",
            "$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":1}""")}",
            "$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":{}}""")}",
            "$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":""}""")}",
            "$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"not-a-uuid"}""")}",
            "$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"$id","unknown":1}""")}",
            "$LINK_ORIGIN/join#",
        )) {
            assertEquals(InviteLinkVerdict.NotAnInvite, InviteLinkText.parse(raw), raw)
        }
    }

    // ---- the path form ----

    @Test
    fun `an encrypted event's invite is the path form carrying its key, and reads back`() {
        val text = InviteLinkText.format(keyed)
        assertEquals("$LINK_ORIGIN/join/$id#k=$keyText", text)
        assertEquals(keyed, invite(text))
    }

    @Test
    fun `a path-form link without a key is a plain invite`() {
        for (raw in listOf(
            "$LINK_ORIGIN/join/$id",
            "$LINK_ORIGIN/join/$id/",
            "$LINK_ORIGIN/join/$id#",
            "$LINK_ORIGIN/join/$id#autoJoin=true&direction=upload",
        )) {
            assertEquals(plain, invite(raw), raw)
        }
    }

    @Test
    fun `a key cut short is an invite whose key cannot be read`() {
        for (raw in listOf(
            "$LINK_ORIGIN/join/$id#k=${keyText.dropLast(5)}",
            "$LINK_ORIGIN/join/$id#k=",
            "$LINK_ORIGIN/join/$id#k",
            "$LINK_ORIGIN/join/$id#garbage",
            "$LINK_ORIGIN/join/$id#k=${EventKeyText.encode(ByteArray(16))}",
        )) {
            assertEquals(event, assertIs<InviteLinkVerdict.KeyUnreadable>(InviteLinkText.parse(raw), raw).event)
        }
    }

    @Test
    fun `a path form that names no event is no invite`() {
        for (raw in listOf(
            "$LINK_ORIGIN/join/",
            "$LINK_ORIGIN/join/not-a-uuid",
            "$LINK_ORIGIN/join/$id/extra",
            "$LINK_ORIGIN/join/$id?x=1",
        )) {
            assertEquals(InviteLinkVerdict.NotAnInvite, InviteLinkText.parse(raw), raw)
        }
    }

    // ---- the Google Play install referrer ----

    @Test
    fun `an install referrer carrying an invite opens it, an encrypted one with its key`() {
        assertEquals(plain, InviteLinkText.fromInstallReferrer(fragmentOf(InviteLinkText.format(plain))))
        assertEquals(
            keyed,
            InviteLinkText.fromInstallReferrer("${fragmentOf(InviteLinkText.format(plain))}&k=$keyText"),
        )
    }

    @Test
    fun `parameters beside the invite are dropped`() {
        assertEquals(plain, InviteLinkText.fromInstallReferrer("v=3&d=${absent("""{"eventId":"$id"}""")}&utm_source=x"))
    }

    @Test
    fun `an organic install, or anything that is not an invite, carries none`() {
        for (referrer in listOf(
            "utm_source=google-play&utm_medium=organic",
            "",
            "garbage",
            "v=2&d=${absent("""{"eventId":"$id"}""")}",
            "v=3&d=not-base64!",
            fragmentOf(InviteLinkText.format(plain)).replace("&", "%26"),
            "${fragmentOf(InviteLinkText.format(plain))}&k=${keyText.dropLast(3)}",
        )) {
            assertNull(InviteLinkText.fromInstallReferrer(referrer), referrer)
        }
    }

    // ---- the key ----

    @Test
    fun `a key's text is one-to-one with the key`() {
        val key = ByteArray(EventKeyText.KEY_BYTES) { (it * 7).toByte() }
        assertContentEquals(key, EventKeyText.decode(EventKeyText.encode(key)))
        assertNull(EventKeyText.decode(EventKeyText.encode(ByteArray(16))), "another length")
        assertNull(EventKeyText.decode("$keyText="), "padded")
        assertNull(EventKeyText.decode("not base64!"))
    }
}
