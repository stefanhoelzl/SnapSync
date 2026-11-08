package app.snapsync.domain.asset

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canonical asset-id rule, and why it is a type: the two id-matching selection rules once went silently
 * inert on device because the facts reader handed a raw PhotoKit `localIdentifier` to sets holding the
 * converted form — the device re-uploaded photos it had downloaded from another event, and the WhatsApp/Telegram
 * denylist did nothing. With [AssetId] a raw identifier cannot be parsed at all, so that mismatch fails at
 * the first id instead of admitting everything in silence.
 */
class AssetIdTest {

    @Test
    fun `the forms every platform mints are canonical`() {
        for (id in listOf(CANONICAL, "ASSET1", "a-b-c", "1000000123", "IMG.HEIC", "tilde~ok", "under_score", ".")) {
            assertTrue(AssetId.isCanonical(id), id)
            assertEquals(id, AssetId.parse(id)?.value)
        }
    }

    @Test
    fun `a raw PhotoKit localIdentifier is not an asset id`() {
        assertFalse(AssetId.isCanonical(RAW))
        assertNull(AssetId.parse(RAW))
    }

    @Test
    fun `everything the backend path segment or the download tag could not carry is refused`() {
        for (id in listOf("", "a/b", "..", "a..b", "a b", "a\nb", "a\n", "a%2Fb", "ä", "a?b", "a#b")) {
            assertFalse(AssetId.isCanonical(id), "'$id'")
            assertNull(AssetId.parse(id), "'$id'")
        }
    }

    @Test
    fun `the id interpolates as itself — and ids order as their text`() {
        assertEquals("$CANONICAL-primary.heic", "${id(CANONICAL)}-primary.heic")
        assertTrue(id("a") < id("b"))
    }

    private fun id(text: String): AssetId = assertNotNull(AssetId.parse(text))

    private companion object {
        /** The exact shape PhotoKit hands back, and its canonical mapping. */
        const val RAW = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168/L0/001"
        const val CANONICAL = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168_L0_001"
    }
}
