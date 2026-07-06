@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.config

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigDeeplinkTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"
    private val sample = EventLinkPayload(eventId = eventId)

    private fun success(raw: String): EventLinkPayload {
        val result = decodeConfigUrl(raw)
        assertTrue(result is ConfigDecodeResult.Success, "expected success, got $result")
        return result.payload
    }

    private fun assertFailure(raw: String) {
        assertTrue(decodeConfigUrl(raw) is ConfigDecodeResult.Failure, "expected failure for: $raw")
    }

    private fun absent(json: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())

    @Test
    fun `encode then decode round-trips the event id`() {
        assertEquals(eventId, success(encodeConfigUrl(sample)).eventId)
    }

    @Test
    fun `encoded url has the canonical shape`() {
        assertTrue(encodeConfigUrl(sample).startsWith("snapsync://config?v=3&d="))
    }

    @Test
    fun `payload with optional padding still decodes`() {
        val padded = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT)
            .encode("""{"eventId":"$eventId"}""".encodeToByteArray())
        assertEquals(eventId, success("snapsync://config?v=3&d=$padded").eventId)
    }

    @Test
    fun `wrong scheme or host fails`() {
        assertFailure("https://config?v=3&d=x")
        assertFailure("snapsync://setup?v=3&d=x")
        assertFailure("totally not a url")
    }

    @Test
    fun `legacy v1 and v2 configs are rejected`() {
        // A stale v=2 S3 payload must be rejected, not silently mis-read — the device re-provisions.
        val s3 = absent("""{"bucket":"b","region":"r","accessKeyId":"a","secretAccessKey":"s"}""")
        assertFailure("snapsync://config?v=2&d=$s3")
        assertFailure("snapsync://config?v=1&d=$s3")
    }

    @Test
    fun `missing version fails`() {
        assertFailure("snapsync://config?d=${absent("""{"eventId":"$eventId"}""")}")
    }

    @Test
    fun `missing payload fails`() {
        assertFailure("snapsync://config?v=3")
    }

    @Test
    fun `undecodable base64url fails`() {
        assertFailure("snapsync://config?v=3&d=!!!not base64!!!")
    }

    @Test
    fun `non-json payload fails`() {
        assertFailure("snapsync://config?v=3&d=${absent("not json")}")
    }

    @Test
    fun `missing eventId key fails`() {
        assertFailure("snapsync://config?v=3&d=${absent("""{}""")}")
    }

    @Test
    fun `empty eventId fails`() {
        assertFailure("snapsync://config?v=3&d=${absent("""{"eventId":""}""")}")
    }

    @Test
    fun `non-uuid eventId fails`() {
        assertFailure("snapsync://config?v=3&d=${absent("""{"eventId":"not-a-uuid"}""")}")
        assertFailure("snapsync://config?v=3&d=${absent("""{"eventId":"1234"}""")}")
    }

    @Test
    fun `uppercase uuid is accepted`() {
        val upper = eventId.uppercase()
        assertEquals(upper, success("snapsync://config?v=3&d=${absent("""{"eventId":"$upper"}""")}").eventId)
    }

    @Test
    fun `unknown extra key fails`() {
        assertFailure("snapsync://config?v=3&d=${absent("""{"eventId":"$eventId","extra":"x"}""")}")
    }

    @Test
    fun `absent autoJoin defaults to false`() {
        assertEquals(false, success(encodeConfigUrl(sample)).autoJoin)
        assertEquals(false, success("snapsync://config?v=3&d=${absent("""{"eventId":"$eventId"}""")}").autoJoin)
    }

    @Test
    fun `autoJoin true decodes`() {
        val payload = success("snapsync://config?v=3&d=${absent("""{"eventId":"$eventId","autoJoin":true}""")}")
        assertEquals(eventId, payload.eventId)
        assertEquals(true, payload.autoJoin)
    }

    @Test
    fun `canonical encode omits autoJoin`() {
        // encodeDefaults is off, so a false autoJoin never appears in a real invite QR.
        assertTrue(!encodeConfigUrl(sample).contains("autoJoin"))
    }

    @Test
    fun `encode with autoJoin round-trips`() {
        val payload = success(encodeConfigUrl(EventLinkPayload(eventId = eventId, autoJoin = true)))
        assertEquals(eventId, payload.eventId)
        assertEquals(true, payload.autoJoin)
    }

    @Test
    fun `absent minPhotoDate defaults to null`() {
        assertEquals(null, success(encodeConfigUrl(sample)).minPhotoDate)
    }

    @Test
    fun `dev cutoff key decodes alongside autoJoin`() {
        val cutoff = "2026-07-06T14:32:11Z"
        val json = """{"eventId":"$eventId","autoJoin":true,"minPhotoDate":"$cutoff"}"""
        val payload = success("snapsync://config?v=3&d=${absent(json)}")
        assertEquals(eventId, payload.eventId)
        assertEquals(true, payload.autoJoin)
        assertEquals(cutoff, payload.minPhotoDate)
    }

    @Test
    fun `canonical encode omits minPhotoDate`() {
        // encodeDefaults is off, so an absent cutoff never appears in a real invite QR.
        assertTrue(!encodeConfigUrl(sample).contains("minPhotoDate"))
    }

    @Test
    fun `unknown key still fails even with the cutoff key present`() {
        val json = """{"eventId":"$eventId","minPhotoDate":"2026-07-06T14:32:11Z","extra":"x"}"""
        assertFailure("snapsync://config?v=3&d=${absent(json)}")
    }
}
