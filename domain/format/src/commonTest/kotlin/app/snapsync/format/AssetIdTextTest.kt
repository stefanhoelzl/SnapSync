package app.snapsync.format

import app.snapsync.domain.asset.AssetId
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one encoding between the platform's asset id and the app's ([AssetIdText]). */
class AssetIdTextTest {

    private fun appId(text: String): AssetId = AssetId.parse(text) ?: error("not canonical: $text")

    @Test
    fun `every id stored before the encoding existed encodes to itself`() {
        // A PhotoKit localIdentifier always became <UUID>_L0_<NNN>, and a media-store id is decimal.
        assertEquals(
            appId("5C33E0C1-4E39-4A1B-9C2D-1F2E3D4C5B6A_L0_001"),
            AssetIdText.fromPlatform("5C33E0C1-4E39-4A1B-9C2D-1F2E3D4C5B6A/L0/001"),
        )
        assertEquals(appId("1000000123"), AssetIdText.fromPlatform("1000000123"))
    }

    @Test
    fun `every platform id has an app id and decodes back to itself`() {
        val ids = listOf(
            "5C33E0C1-4E39/L0/001",
            "with_underscore/L0/001",
            "tilde~and_underscore",
            "a..b...c.",
            "spaces and: colons",
            "ünïcödé",
            ".",
        )
        for (raw in ids) {
            val id = AssetIdText.fromPlatform(raw)
            assertEquals(raw, AssetIdText.platform(id), "via ${id.value}")
        }
        assertEquals(appId("a~_b"), AssetIdText.fromPlatform("a_b"), "an underscore is not a slash")
        assertEquals(appId("a~~b"), AssetIdText.fromPlatform("a~b"))
        assertEquals(appId("a.~.b"), AssetIdText.fromPlatform("a..b"), "no app id holds two dots in a row")
        assertEquals(appId("a~0020b"), AssetIdText.fromPlatform("a b"))
    }
}
