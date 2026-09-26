package app.snapsync.gallery

import app.snapsync.model.AssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one PhotoKit ↔ [AssetId] mapping. Its exactness is what lets the reader fetch an asset back by the id it
 * handed out, and what makes the importer's suppression marker meet the id the next walk produces.
 */
class PhotoKitAssetIdsTest {

    @Test
    fun a_local_identifier_maps_to_its_canonical_id_and_back() {
        val id = PhotoKitAssetIds.assetIdOf(RAW)
        assertEquals(AssetId("5C33E0C1-4E39-4EE0-891F-BAFB943BC168_L0_001"), id)
        assertEquals(RAW, PhotoKitAssetIds.localIdentifierOf(id!!))
    }

    @Test
    fun an_identifier_the_mapping_could_not_invert_has_no_id() {
        // `_` would come back as `/` and fetch a different asset, so it is refused rather than guessed.
        assertNull(PhotoKitAssetIds.assetIdOf("5C33E0C1_4E39/L0/001"))
        // Anything the canonical rule refuses once `/` is mapped.
        assertNull(PhotoKitAssetIds.assetIdOf("5C33E0C1 4E39/L0/001"))
        assertNull(PhotoKitAssetIds.assetIdOf(""))
    }

    private companion object {
        const val RAW = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168/L0/001"
    }
}
