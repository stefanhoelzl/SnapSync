package app.snapsync.gallery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Photos.PHAssetMediaSubtypePhotoHDR
import platform.Photos.PHAssetMediaSubtypePhotoLive
import platform.Photos.PHAssetMediaSubtypePhotoPanorama
import platform.Photos.PHAssetMediaSubtypePhotoScreenshot
import platform.Photos.PHAssetMediaSubtypeVideoScreenRecording

/**
 * The PhotoKit → neutral-facts interpretation (capability `gallery-status`).
 *
 * This runs on the **simulator**, not the JVM, and that is the whole point: it asserts the pinned
 * constants against the real SDK symbols. The equivalent `commonTest` would compare one copy of a
 * literal to another copy and pass while both were wrong — the drift `RuntimeIdentityTest` exists to
 * catch. Moving the interpretation here is what makes this test possible.
 *
 * `PHAsset` cannot be constructed, so the mask semantics are exercised directly rather than through
 * `toAssetFacts` — the bits are the part that can be wrong.
 */
class PhotoKitAssetFactsTest {

    @Test
    fun `the pinned subtype bits match the SDK`() {
        assertEquals(PHAssetMediaSubtypePhotoScreenshot.toLong(), SUBTYPE_SCREENSHOT)
        assertEquals(PHAssetMediaSubtypeVideoScreenRecording.toLong(), SUBTYPE_SCREEN_RECORDING)
    }

    @Test
    fun `the exclusion mask covers exactly the two excluded subtypes`() {
        assertEquals(SUBTYPE_SCREENSHOT or SUBTYPE_SCREEN_RECORDING, EXCLUDED_SUBTYPE_MASK)
    }

    @Test
    fun `camera subtypes are not caught by the exclusion mask`() {
        // The expensive direction. Panorama, HDR and Live Photo are all camera captures; a mask that
        // caught any of them would silently delete real event photos from every member's contribution.
        val cameraSubtypes = PHAssetMediaSubtypePhotoPanorama.toLong() or
            PHAssetMediaSubtypePhotoHDR.toLong() or
            PHAssetMediaSubtypePhotoLive.toLong()
        assertEquals(0L, cameraSubtypes and EXCLUDED_SUBTYPE_MASK)
    }

    @Test
    fun `each excluded subtype is caught on its own`() {
        assertTrue(PHAssetMediaSubtypePhotoScreenshot.toLong() and EXCLUDED_SUBTYPE_MASK != 0L)
        assertTrue(PHAssetMediaSubtypeVideoScreenRecording.toLong() and EXCLUDED_SUBTYPE_MASK != 0L)
    }

    @Test
    fun `a screenshot carrying an additional camera subtype is still caught`() {
        // Subtypes are a bitmask, not an enum: an asset can carry several. The mask must test bits, not
        // equality — an `== SUBTYPE_SCREENSHOT` comparison would let a combined value straight through.
        val combined = PHAssetMediaSubtypePhotoScreenshot.toLong() or PHAssetMediaSubtypePhotoHDR.toLong()
        assertTrue(combined and EXCLUDED_SUBTYPE_MASK != 0L)
    }
}
