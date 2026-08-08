package app.snapsync.gallery

import app.snapsync.model.ResourceRole
import platform.Photos.PHAssetResourceTypeAdjustmentBasePhoto
import platform.Photos.PHAssetResourceTypeAdjustmentData
import platform.Photos.PHAssetResourceTypeAlternatePhoto
import platform.Photos.PHAssetResourceTypeAudio
import platform.Photos.PHAssetResourceTypeFullSizePairedVideo
import platform.Photos.PHAssetResourceTypeFullSizePhoto
import platform.Photos.PHAssetResourceTypeFullSizeVideo
import platform.Photos.PHAssetResourceTypePairedVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `PHAssetResourceType` → [ResourceRole] table (capability `gallery-status`).
 *
 * These assertions used to live in `:domain`'s `commonTest` as bare integers compared to bare
 * integers (`resourceRole(1L)`, `resourceRole(9L)`) — a table over Apple's ABI that no JVM run could
 * disagree with, and that no gate could see, because an ABI decoder written in primitives is
 * indistinguishable from arithmetic. Here they name the SDK's own constants, so a value moving under
 * us is a red test rather than a silently dropped resource.
 */
class PhotoKitResourceRoleTest {

    @Test
    fun `original resource types map to roles`() {
        assertEquals(ResourceRole.PRIMARY, photoKitResourceRole(PHAssetResourceTypePhoto))
        assertEquals(ResourceRole.PRIMARY, photoKitResourceRole(PHAssetResourceTypeVideo))
        assertEquals(ResourceRole.PRIMARY, photoKitResourceRole(PHAssetResourceTypeAudio))
        assertEquals(ResourceRole.LIVE, photoKitResourceRole(PHAssetResourceTypePairedVideo))
    }

    @Test
    fun `edit artifacts and raw alternates are dropped`() {
        val dropped = listOf(
            PHAssetResourceTypeAlternatePhoto, // RAW
            PHAssetResourceTypeFullSizePhoto,
            PHAssetResourceTypeFullSizeVideo,
            PHAssetResourceTypeAdjustmentData,
            PHAssetResourceTypeAdjustmentBasePhoto,
            PHAssetResourceTypeFullSizePairedVideo,
        )
        dropped.forEach { assertNull(photoKitResourceRole(it), "type $it should be dropped") }
    }

    @Test
    fun `a future or unknown type is dropped rather than guessed`() {
        // Absence is one answer for "unknown" and "deliberately not carried" — the caller skips the
        // resource either way, so the collapse costs nothing.
        assertNull(photoKitResourceRole(99L))
    }

    @Test
    fun `an edited live photo yields only its originals`() {
        // An edited Live Photo exposes its originals (photo + pairedVideo) alongside edit artifacts
        // (a full-size render, adjustment data, a full-size paired video). Only the originals survive.
        val exposed = listOf(
            PHAssetResourceTypePhoto,
            PHAssetResourceTypePairedVideo,
            PHAssetResourceTypeFullSizePhoto,
            PHAssetResourceTypeAdjustmentData,
            PHAssetResourceTypeFullSizePairedVideo,
        )
        assertEquals(
            listOf(ResourceRole.PRIMARY, ResourceRole.LIVE),
            exposed.mapNotNull { photoKitResourceRole(it) },
        )
    }
}
