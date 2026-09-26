package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The canonical asset-id rule, and why it is a type: the two id-matching selection rules once went silently
 * inert on device because the facts reader handed a raw PhotoKit `localIdentifier` to sets holding the
 * converted form — the device re-uploaded photos it had downloaded from another event, and the WhatsApp/Telegram
 * denylist did nothing. With [AssetId] a raw identifier cannot be constructed at all, so that mismatch fails at
 * the first id instead of admitting everything in silence.
 */
class AssetIdTest {

    @Test
    fun `the forms every platform mints are canonical`() {
        for (id in listOf(CANONICAL, "ASSET1", "a-b-c", "1000000123", "IMG.HEIC", "tilde~ok")) {
            assertTrue(isCanonicalAssetId(id), id)
            assertEquals(id, AssetId(id).value)
        }
    }

    @Test
    fun `a raw PhotoKit localIdentifier is not an asset id`() {
        assertFalse(isCanonicalAssetId(RAW))
        assertFailsWith<IllegalArgumentException> { AssetId(RAW) }
    }

    @Test
    fun `everything the backend path segment or the download tag could not carry is refused`() {
        for (id in listOf("", "a/b", "..", "a..b", "a b", "a\nb", "a%2Fb", "ä", "a?b", "a#b")) {
            assertFalse(isCanonicalAssetId(id), "'$id'")
        }
    }

    @Test
    fun `the id interpolates as itself`() {
        assertEquals("$CANONICAL-primary.heic", "${AssetId(CANONICAL)}-primary.heic")
    }

    @Test
    fun `the echo and album rules match on the one form`() {
        val facts = AssetFacts(AssetId(CANONICAL), CaptureDate("2026-07-21T07:46:58Z"))
        assertFalse(SelectionRule.NotEcho(setOf(AssetId(CANONICAL))).admits(facts))
        assertFalse(SelectionRule.NotInDenylistedAlbum(setOf(AssetId(CANONICAL))).admits(facts))
    }

    private companion object {
        /** The exact shape PhotoKit hands back, and its canonical mapping. */
        const val RAW = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168/L0/001"
        const val CANONICAL = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168_L0_001"
    }
}
