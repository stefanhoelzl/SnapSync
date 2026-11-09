@file:OptIn(RendersInvite::class)

package app.snapsync.format

import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.invite.RendersInvite
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An invite link's text (capability `join-event`): the one codec, both forms, never a throw. */
class InviteLinkTextTest {

    private val id = "11111111-1111-4111-8111-111111111111"
    private val event = EventId.parse(id)!!
    private val plain = InviteLink(event, null)

    @OptIn(MakesEventKey::class)
    private val key = EventKey.of(ByteArray(EventKey.LENGTH) { it.toByte() })!!
    private val keyText = EventKeyText.encode(key)
    private val keyed = InviteLink(event, key)

    /** [bytes] spelled as a key would be, whatever their length. */
    private fun spelled(bytes: ByteArray) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

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
        val text = InviteLinkText.format(plain).raw
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
        val text = InviteLinkText.format(keyed).raw
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
            "$LINK_ORIGIN/join/$id#k=${spelled(ByteArray(16))}",
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
            // A query is no longer one: it is read past (an encrypted event's shared invite carries its preview name in
            // one, `changes/seal-event-name`), so `/join/<id>?x=1` is the invite `/join/<id>` — pinned below.
        )) {
            assertEquals(InviteLinkVerdict.NotAnInvite, InviteLinkText.parse(raw), raw)
        }
    }

    // ---- the Google Play install referrer ----

    @Test
    fun `an install referrer carrying an invite opens it, an encrypted one with its key`() {
        assertEquals(plain, InviteLinkText.fromInstallReferrer(fragmentOf(InviteLinkText.format(plain).raw)))
        assertEquals(
            keyed,
            InviteLinkText.fromInstallReferrer("${fragmentOf(InviteLinkText.format(plain).raw)}&k=$keyText"),
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
            fragmentOf(InviteLinkText.format(plain).raw).replace("&", "%26"),
            "${fragmentOf(InviteLinkText.format(plain).raw)}&k=${keyText.dropLast(3)}",
        )) {
            assertNull(InviteLinkText.fromInstallReferrer(referrer), referrer)
        }
    }

    // ---- the key ----

    @Test
    fun `a key's text is one-to-one with the key`() {
        assertEquals(key, EventKeyText.decode(EventKeyText.encode(key)))
        assertEquals(spelled(ByteArray(EventKey.LENGTH) { it.toByte() }), keyText, "unpadded base64url")
        assertNull(EventKeyText.decode(spelled(ByteArray(16))), "another length")
        assertNull(EventKeyText.decode("$keyText="), "padded")
        assertNull(EventKeyText.decode("not base64!"))
    }

    // ---- the preview name an encrypted event's shared invite carries (decision record `changes/seal-event-name`) ----

    @OptIn(RendersInvite::class)
    @Test
    fun `a shared encrypted invite carries its name before the fragment, percent-encoded`() {
        val shared = InviteLinkText.shared(keyed, "Ana's 30th & co #1 %").raw
        assertEquals("$LINK_ORIGIN/join/$id?n=Ana%27s%2030th%20%26%20co%20%231%20%25#k=$keyText", shared)
        assertEquals(keyed, invite(shared), "it opens the same invite as its QR code")
        val emoji = InviteLinkText.shared(keyed, "🎉 Fête").raw
        assertEquals("$LINK_ORIGIN/join/$id?n=%F0%9F%8E%89%20F%C3%AAte#k=$keyText", emoji)
        assertEquals(keyed, invite(emoji))
        val marks = InviteLinkText.shared(keyed, "a-b_c.d~e").raw
        assertEquals(
            "$LINK_ORIGIN/join/$id?n=a-b_c.d~e#k=$keyText",
            marks,
            "RFC 3986's unreserved marks stay as they are",
        )
    }

    @OptIn(RendersInvite::class)
    @Test
    fun `the QR code's invite carries no name, and a plain event's shared invite is its plain one`() {
        assertEquals("$LINK_ORIGIN/join/$id#k=$keyText", InviteLinkText.format(keyed).raw)
        assertEquals(InviteLinkText.format(plain), InviteLinkText.shared(plain, "Picnic"))
    }

    @Test
    fun `any query is read past - a stale, edited, empty or malformed name opens the same invite`() {
        for (query in listOf("?n=Old%20name", "?n=", "?n=%ZZ", "?", "?x=1&n=a&n=b", "?n=a?b")) {
            assertEquals(keyed, invite("$LINK_ORIGIN/join/$id$query#k=$keyText"), query)
            assertEquals(plain, invite("$LINK_ORIGIN/join/$id$query"), "$query, with no key")
        }
        assertEquals(keyed, invite("$LINK_ORIGIN/join/$id/?n=a#k=$keyText"), "a trailing slash too")
    }

    @Test
    fun `a query never rescues an id that is none`() {
        assertEquals(InviteLinkVerdict.NotAnInvite, InviteLinkText.parse("$LINK_ORIGIN/join/not-an-id?n=x#k=$keyText"))
    }
}
