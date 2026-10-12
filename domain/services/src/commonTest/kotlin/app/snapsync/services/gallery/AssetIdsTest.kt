package app.snapsync.services.gallery

import app.snapsync.model.AssetId
import app.snapsync.ports.PlatformAssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The one encoding between the platform's asset id and the app's ([assetId], [platformId]). */
class AssetIdsTest {

    @Test
    fun `every id stored before the encoding existed encodes to itself`() {
        // A PhotoKit localIdentifier always became <UUID>_L0_<NNN>, and a media-store id is decimal.
        assertEquals(
            AssetId("5C33E0C1-4E39-4A1B-9C2D-1F2E3D4C5B6A_L0_001"),
            PlatformAssetId("5C33E0C1-4E39-4A1B-9C2D-1F2E3D4C5B6A/L0/001").assetId(),
        )
        assertEquals(AssetId("1000000123"), PlatformAssetId("1000000123").assetId())
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
            val id = PlatformAssetId(raw).assetId()
            assertEquals(raw, id.platformId().value, "via ${id.value}")
        }
        assertEquals(AssetId("a~_b"), PlatformAssetId("a_b").assetId(), "an underscore is not a slash")
        assertEquals(AssetId("a~~b"), PlatformAssetId("a~b").assetId())
        assertEquals(AssetId("a.~.b"), PlatformAssetId("a..b").assetId(), "no app id holds two dots in a row")
        assertEquals(AssetId("a~0020b"), PlatformAssetId("a b").assetId())
    }

    @Test
    fun `an empty platform id is the adapter's error`() {
        assertFailsWith<IllegalArgumentException> { PlatformAssetId("").assetId() }
    }
}
