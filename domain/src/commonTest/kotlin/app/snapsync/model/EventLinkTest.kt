@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventLinkTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"
    private val sample = EventLinkPayload(eventId = eventId)

    private fun success(raw: String): EventLinkPayload {
        val result = decodeEventUrl(raw)
        assertTrue(result is ConfigDecodeResult.Success, "expected success, got $result")
        return result.payload
    }

    private fun assertFailure(raw: String) {
        assertTrue(decodeEventUrl(raw) is ConfigDecodeResult.Failure, "expected failure for: $raw")
    }

    private fun absent(json: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())

    @Test
    fun `encode then decode round-trips the event id`() {
        assertEquals(eventId, success(encodeEventUrl(sample)).eventId)
    }

    @Test
    fun `encoded url has the canonical shape`() {
        assertTrue(encodeEventUrl(sample).startsWith("$LINK_ORIGIN/join#v=3&d="))
    }

    @Test
    fun `the payload rides in the fragment and the query stays empty`() {
        // Load-bearing, not cosmetic: a browser never sends the fragment, so an invite opened WITHOUT
        // the app reaches the backend as a bare `GET /join` and the eventId — the upload capability —
        // never lands in a server log or a cache key. Moving the payload to `?` would look like tidying
        // and would silently forfeit that.
        val url = encodeEventUrl(sample)
        val afterOrigin = url.removePrefix(LINK_ORIGIN)
        assertEquals("/join", afterOrigin.substringBefore('#'))
        assertTrue('?' !in url, "the event link must carry no query component: $url")
        assertTrue(afterOrigin.substringAfter('#').startsWith("v=3&d="))
    }

    @Test
    fun `a retired snapsync scheme link is rejected`() {
        // The custom scheme is gone. A link shared before the migration must fail closed and visibly
        // (the create screen's invalid-link error), never provision.
        assertFailure("snapsync://config?v=3&d=${absent("""{"eventId":"$eventId"}""")}")
    }

    @Test
    fun `payload with optional padding still decodes`() {
        val padded = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT)
            .encode("""{"eventId":"$eventId"}""".encodeToByteArray())
        assertEquals(eventId, success("$LINK_ORIGIN/join#v=3&d=$padded").eventId)
    }

    @Test
    fun `a foreign origin or wrong path fails`() {
        assertFailure("https://evil.example/join#v=3&d=x")
        assertFailure("$LINK_ORIGIN/joinx#v=3&d=x")
        assertFailure("$LINK_ORIGIN/#v=3&d=x")
        assertFailure("totally not a url")
    }

    @Test
    fun `legacy v1 and v2 configs are rejected`() {
        // A stale v=2 S3 payload must be rejected, not silently mis-read — the device re-provisions.
        val s3 = absent("""{"bucket":"b","region":"r","accessKeyId":"a","secretAccessKey":"s"}""")
        assertFailure("$LINK_ORIGIN/join#v=2&d=$s3")
        assertFailure("$LINK_ORIGIN/join#v=1&d=$s3")
    }

    @Test
    fun `missing version fails`() {
        assertFailure("$LINK_ORIGIN/join#d=${absent("""{"eventId":"$eventId"}""")}")
    }

    @Test
    fun `missing payload fails`() {
        assertFailure("$LINK_ORIGIN/join#v=3")
    }

    @Test
    fun `undecodable base64url fails`() {
        assertFailure("$LINK_ORIGIN/join#v=3&d=!!!not base64!!!")
    }

    @Test
    fun `non-json payload fails`() {
        assertFailure("$LINK_ORIGIN/join#v=3&d=${absent("not json")}")
    }

    @Test
    fun `missing eventId key fails`() {
        assertFailure("$LINK_ORIGIN/join#v=3&d=${absent("""{}""")}")
    }

    @Test
    fun `empty eventId fails`() {
        assertFailure("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":""}""")}")
    }

    @Test
    fun `non-uuid eventId fails`() {
        assertFailure("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"not-a-uuid"}""")}")
        assertFailure("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"1234"}""")}")
    }

    @Test
    fun `uppercase uuid is accepted`() {
        val upper = eventId.uppercase()
        assertEquals(upper, success("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"$upper"}""")}").eventId)
    }

    @Test
    fun `unknown extra key fails`() {
        assertFailure("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"$eventId","extra":"x"}""")}")
    }

    @Test
    fun `absent autoJoin defaults to false`() {
        assertEquals(false, success(encodeEventUrl(sample)).autoJoin)
        assertEquals(false, success("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"$eventId"}""")}").autoJoin)
    }

    @Test
    fun `autoJoin true decodes`() {
        val payload = success("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"$eventId","autoJoin":true}""")}")
        assertEquals(eventId, payload.eventId)
        assertEquals(true, payload.autoJoin)
    }

    @Test
    fun `canonical encode omits autoJoin`() {
        // encodeDefaults is off, so a false autoJoin never appears in a real invite QR.
        assertTrue(!encodeEventUrl(sample).contains("autoJoin"))
    }

    @Test
    fun `encode with autoJoin round-trips`() {
        val payload = success(encodeEventUrl(EventLinkPayload(eventId = eventId, autoJoin = true)))
        assertEquals(eventId, payload.eventId)
        assertEquals(true, payload.autoJoin)
    }

    @Test
    fun `absent minPhotoDate defaults to null`() {
        assertEquals(null, success(encodeEventUrl(sample)).minPhotoDate)
    }

    @Test
    fun `dev cutoff key decodes alongside autoJoin`() {
        val cutoff = "2026-07-06T14:32:11Z"
        val json = """{"eventId":"$eventId","autoJoin":true,"minPhotoDate":"$cutoff"}"""
        val payload = success("$LINK_ORIGIN/join#v=3&d=${absent(json)}")
        assertEquals(eventId, payload.eventId)
        assertEquals(true, payload.autoJoin)
        assertEquals(cutoff, payload.minPhotoDate)
    }

    @Test
    fun `canonical encode omits minPhotoDate`() {
        // encodeDefaults is off, so an absent cutoff never appears in a real invite QR.
        assertTrue(!encodeEventUrl(sample).contains("minPhotoDate"))
    }

    @Test
    fun `unknown key still fails even with the cutoff key present`() {
        val json = """{"eventId":"$eventId","minPhotoDate":"2026-07-06T14:32:11Z","extra":"x"}"""
        assertFailure("$LINK_ORIGIN/join#v=3&d=${absent(json)}")
    }

    @Test
    fun `absent direction defaults to null`() {
        assertEquals(null, success(encodeEventUrl(sample)).direction)
        assertEquals(null, success("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"$eventId"}""")}").direction)
    }

    @Test
    fun `dev direction key decodes alongside autoJoin`() {
        val json = """{"eventId":"$eventId","autoJoin":true,"direction":"download"}"""
        val payload = success("$LINK_ORIGIN/join#v=3&d=${absent(json)}")
        assertEquals(eventId, payload.eventId)
        assertEquals(true, payload.autoJoin)
        assertEquals("download", payload.direction)
        assertEquals(Direction.DownloadOnly, Direction.fromWire(payload.direction!!))
    }

    @Test
    fun `each direction wire token decodes`() {
        for ((token, expected) in listOf(
            "both" to Direction.Both,
            "upload" to Direction.UploadOnly,
            "download" to Direction.DownloadOnly,
        )) {
            val payload = success("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"$eventId","direction":"$token"}""")}")
            assertEquals(expected, Direction.fromWire(payload.direction!!))
        }
    }

    @Test
    fun `an unknown direction token fails`() {
        assertFailure("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"$eventId","direction":"sideways"}""")}")
        assertFailure("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"$eventId","direction":""}""")}")
    }

    @Test
    fun `canonical encode omits direction`() {
        // encodeDefaults is off, so an absent direction never appears in a real invite QR.
        assertTrue(!encodeEventUrl(sample).contains("direction"))
    }

    @Test
    fun `encode with direction round-trips`() {
        val payload = success(encodeEventUrl(EventLinkPayload(eventId = eventId, direction = "upload")))
        assertEquals("upload", payload.direction)
    }

    @Test
    fun `absent saveToAlbum defaults to null`() {
        assertEquals(null, success(encodeEventUrl(sample)).saveToAlbum)
        assertEquals(null, success("$LINK_ORIGIN/join#v=3&d=${absent("""{"eventId":"$eventId"}""")}").saveToAlbum)
    }

    @Test
    fun `dev saveToAlbum key decodes alongside autoJoin`() {
        val json = """{"eventId":"$eventId","autoJoin":true,"saveToAlbum":true}"""
        val payload = success("$LINK_ORIGIN/join#v=3&d=${absent(json)}")
        assertEquals(true, payload.autoJoin)
        assertEquals(true, payload.saveToAlbum)
    }

    @Test
    fun `canonical encode omits saveToAlbum`() {
        // encodeDefaults is off, so an absent saveToAlbum never appears in a real invite QR.
        assertTrue(!encodeEventUrl(sample).contains("saveToAlbum"))
    }

    @Test
    fun `encode with saveToAlbum round-trips`() {
        val payload = success(encodeEventUrl(EventLinkPayload(eventId = eventId, saveToAlbum = true)))
        assertEquals(true, payload.saveToAlbum)
    }
}
