@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreateDirectiveTest {

    private fun b64(json: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())

    private fun success(raw: String): CreateEventPayload {
        val result = decodeCreateDirective(raw)
        assertTrue(result is CreateDecodeResult.Success, "expected success, got $result")
        return result.payload
    }

    private fun assertFailure(raw: String) {
        assertTrue(decodeCreateDirective(raw) is CreateDecodeResult.Failure, "expected failure for: $raw")
    }

    @Test
    fun `a minimal payload decodes with only a name and inert defaults`() {
        val p = success(b64("""{"name":"Anna's Birthday"}"""))
        assertEquals("Anna's Birthday", p.name)
        assertNull(p.startsAt)
        assertEquals(false, p.autoJoin)
        assertNull(p.minPhotoDate)
        assertNull(p.direction)
        assertNull(p.saveToAlbum)
    }

    @Test
    fun `all optional keys decode`() {
        val p = success(
            b64(
                """{"name":"Party","startsAt":"2026-07-14T18:00:00Z","autoJoin":true,""" +
                    """"minPhotoDate":"2026-07-14T18:00:00Z","direction":"download","saveToAlbum":true}""",
            ),
        )
        assertEquals("Party", p.name)
        assertEquals("2026-07-14T18:00:00Z", p.startsAt)
        assertEquals(true, p.autoJoin)
        assertEquals("2026-07-14T18:00:00Z", p.minPhotoDate)
        assertEquals("download", p.direction)
        assertEquals(true, p.saveToAlbum)
    }

    @Test
    fun `padded base64url still decodes`() {
        // The encoder emits no padding; the decoder must accept it either way.
        val padded = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT)
            .encode("""{"name":"X"}""".encodeToByteArray())
        assertEquals("X", success(padded).name)
    }

    @Test
    fun `an unknown key is rejected`() {
        assertFailure(b64("""{"name":"X","bogus":1}"""))
    }

    @Test
    fun `a missing name is rejected`() {
        assertFailure(b64("""{"autoJoin":true}"""))
    }

    @Test
    fun `a blank name is rejected`() {
        assertFailure(b64("""{"name":"   "}"""))
    }

    @Test
    fun `a direction outside the known tokens is rejected`() {
        assertFailure(b64("""{"name":"X","direction":"sideways"}"""))
    }

    @Test
    fun `each known direction token is accepted`() {
        for (token in Direction.entries.map { it.wire }) {
            assertEquals(token, success(b64("""{"name":"X","direction":"$token"}""")).direction)
        }
    }

    @Test
    fun `malformed base64url is rejected`() {
        assertFailure("!!!not base64!!!")
    }

    @Test
    fun `valid base64url of non-JSON bytes is rejected`() {
        assertFailure(b64("this is not json"))
    }
}
