package app.snapsync.rig.gallery

import app.snapsync.rig.CensusView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSPredicate
import platform.Photos.PHAsset
import platform.Photos.PHFetchOptions

/**
 * The bitmask values `PHAssetMediaSubtype` uses for the two origins the policy subtracts. Written as
 * literals because the census below uses the **SELECT** predicate form (`(mediaSubtypes & N) != 0`), not
 * the exclusion form the production fetch uses, and the point is to look for these directly.
 */
private const val SUBTYPE_SCREENSHOT = 4
private const val SUBTYPE_SCREEN_RECORDING = 524_288

/**
 * The app host's raw subtype census for [GalleryReport]: PhotoKit, with no predicate for the total and the
 * SELECT form per subtype. Why it bypasses the policy seam is on [GalleryReport].
 */
@OptIn(ExperimentalForeignApi::class)
fun photoKitCensus(): CensusView = CensusView(
    total = PHAsset.fetchAssetsWithOptions(null).count.toLong(),
    screenshots = countMatching(SUBTYPE_SCREENSHOT),
    screenRecordings = countMatching(SUBTYPE_SCREEN_RECORDING),
)

@OptIn(ExperimentalForeignApi::class)
private fun countMatching(subtype: Int): Long {
    val options = PHFetchOptions()
    options.predicate = NSPredicate.predicateWithFormat("(mediaSubtypes & $subtype) != 0", argumentArray = null)
    return PHAsset.fetchAssetsWithOptions(options).count.toLong()
}
