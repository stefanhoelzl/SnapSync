package app.snapsync.model

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.format.TimestampText
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Why an asset id is a type, at its consumers: the two id-matching selection rules once went silently inert on device
 * because the facts reader handed a raw PhotoKit `localIdentifier` to sets holding the converted form. They match on
 * the one canonical form (the rule itself is `AssetIdTest` in the domain layer).
 */
class AssetIdTest {

    @Test
    fun `the echo and album rules match on the one form`() {
        val facts = AssetFacts(AssetId(CANONICAL), CaptureDate.Dated(TimestampText.parse("2026-07-21T07:46:58Z")))
        assertFalse(SelectionRule.NotEcho(setOf(AssetId(CANONICAL))).admits(facts))
        assertFalse(SelectionRule.NotInDenylistedAlbum(setOf(AssetId(CANONICAL))).admits(facts))
    }

    private companion object {
        const val CANONICAL = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168_L0_001"
    }
}
