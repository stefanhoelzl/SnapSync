package app.snapsync.domain.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a report may leave as: filed under the build it describes, grouped as captured, and bounded — nothing scrubbed. */
class CrashShapingTest {

    private val shaping = CrashShaping.STANDARD
    private val id = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `ids leave in full`() {
        val shaped = shaping.event(
            CrashEvent(
                message = "reconcile($id) failed",
                formatted = "reconcile($id) failed",
                breadcrumbs = listOf(Crumb(CrashLevel.INFO, "joined $id", data = mapOf("event" to id))),
            ),
        )
        assertEquals("reconcile($id) failed", shaped.message)
        assertEquals("reconcile($id) failed", shaped.formatted)
        assertEquals("joined $id", shaped.breadcrumbs.single().message)
        assertEquals(mapOf("event" to id), shaped.breadcrumbs.single().data)
    }

    @Test
    fun `an event is filed under the build it describes, and the tags that name it never leave`() {
        val shaped = shaping.event(
            CrashEvent(
                message = "process metric crossed: x",
                tags = mapOf(CrashTags.DESCRIBED_RELEASE to "0.5", CrashTags.DESCRIBED_BUILD to "2200", "keep" to "1"),
                release = "0.6",
                dist = "2400",
            ),
        )
        assertEquals("0.5", shaped.release)
        assertEquals("2200", shaped.dist)
        assertEquals(mapOf("keep" to "1"), shaped.tags)
    }

    @Test
    fun `an event that names no build keeps the channel's, and one naming half keeps the other half`() {
        val none = shaping.event(CrashEvent(message = "x", release = "0.6", dist = "2400"))
        assertEquals("0.6" to "2400", none.release to none.dist)
        val half = shaping.event(CrashEvent(tags = mapOf(CrashTags.DESCRIBED_BUILD to "2200"), release = "0.6"))
        assertEquals("0.6" to "2200", half.release to half.dist)
    }

    @Test
    fun `an event groups under what it was captured with, and the tag carrying it never leaves`() {
        val shaped = shaping.event(CrashEvent(message = "x", tags = mapOf(CrashTags.FINGERPRINT to "engine: x")))
        assertEquals(listOf("engine: x"), shaped.fingerprint)
        assertEquals(emptyMap(), shaped.tags)
        assertEquals(emptyList(), shaping.event(CrashEvent(message = "y")).fingerprint, "else the backend's own")
    }

    @Test
    fun `a runaway message is capped and says so`() {
        val shaped = shaping.event(CrashEvent(message = "x".repeat(20_000), formatted = "y".repeat(20_000)))
        listOfNotNull(shaped.message, shaped.formatted).forEach { text ->
            assertTrue(text.encodeToByteArray().size <= EventBounds.EVENT_TEXT_BYTES && "…[+" in text)
        }
        assertNull(shaping.event(CrashEvent()).message)
    }

    @Test
    fun `a breadcrumb's message and data share one cap, message first`() {
        val shaped = shaping.crumb(
            Crumb(CrashLevel.INFO, "m".repeat(400), "http", mapOf("url" to "u".repeat(400), "status" to "200")),
        )
        assertEquals("m".repeat(400), shaped.message)
        val total = (listOfNotNull(shaped.message) + shaped.data.values).sumOf { it.encodeToByteArray().size }
        assertTrue(total <= EventBounds.BREADCRUMB_TEXT_BYTES, "the whole crumb fits: $total")
        assertEquals(setOf("url", "status"), shaped.data.keys)
        assertEquals("http", shaped.category)
    }

    @Test
    fun `a breadcrumb with no message is data alone`() {
        val shaped = shaping.crumb(Crumb(CrashLevel.DEBUG, null, data = mapOf("k" to "v")))
        assertNull(shaped.message)
        assertEquals(mapOf("k" to "v"), shaped.data)
    }
}
