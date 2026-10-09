package app.snapsync.wire.runtime

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireTest {

    @Serializable
    private class Thing(val name: String, val note: String? = null)

    @Test
    fun a_request_carries_what_it_was_built_with() {
        val r = WireRequest("PUT", "/events/e", mapOf("Content-Type" to "application/json"), "{}")
        assertEquals(
            listOf("PUT", "/events/e", "application/json", "{}"),
            listOf(r.method, r.path, r.headers["Content-Type"], r.body),
        )
    }

    @Test
    fun a_header_is_found_whatever_its_case() {
        val r = WireResponse(200, mapOf("SnapSync-Cursor" to "7"), "")
        assertEquals("7", r.header("snapsync-cursor"))
        assertNull(r.header("Retry-After"))
    }

    @Test
    fun the_media_type_is_compared_without_its_parameters() {
        val r = WireResponse(409, mapOf("content-type" to "text/plain; charset=UTF-8"), "not a member")
        assertTrue(r.isMedia("text/plain"))
        assertFalse(r.isMedia("application/json"))
        assertFalse(WireResponse(409, emptyMap(), "").isMedia("text/plain"))
    }

    @Test
    fun a_body_decodes_or_says_why_not() {
        assertEquals("a", decoded("""{"name":"a","extra":1}"""))
        assertTrue(decoded("<html>").startsWith("undecodable: "))
        assertTrue("name" in decoded("""{"note":"no name"}"""))
    }

    private fun decoded(body: String): String =
        WireResponse(200, emptyMap(), body).json(Thing.serializer()) { return "undecodable: $it" }.name

    @Test
    fun a_null_is_omitted_when_encoding() {
        assertEquals("""{"name":"a"}""", WireJson.encodeToString(Thing.serializer(), Thing("a")))
    }

    @Test
    fun a_segment_keeps_the_unreserved_characters_and_encodes_the_rest_as_utf8() {
        assertEquals("IMG_0001-a.b~c", encodeSegment("IMG_0001-a.b~c"))
        assertEquals("IMG%20001%2Fx%3F", encodeSegment("IMG 001/x?"))
        assertEquals("%C3%A9", encodeSegment("é"))
    }

    @Test
    fun a_query_carries_only_what_is_present() {
        assertEquals("", query("cursor" to null))
        assertEquals("?urls=false&cursor=12", query("urls" to "false", "cursor" to "12"))
        assertEquals("?filename=a%20b.jpg", query("x" to null, "filename" to "a b.jpg"))
    }
}
