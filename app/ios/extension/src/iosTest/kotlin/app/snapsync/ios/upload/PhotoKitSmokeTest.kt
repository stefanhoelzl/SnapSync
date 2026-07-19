package app.snapsync.ios.upload

import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHPhotoLibrary
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A smoke test that the **general PhotoKit surface** our adapter relies on is available and callable
 * on the iOS simulator — it links, and the calls return rather than trap. It needs no photo
 * authorization: `authorizationStatus` is always answerable, and `fetchAssets` returns an empty
 * result (not a trap) in the unauthorized state.
 *
 * It deliberately does NOT touch the cloud-identifier mapping or the background-upload-job APIs:
 * those are part of the simulator-unavailable / iCloud-dependent subsystems and are verified on a
 * real device, not here. This test exists to prove the enumeration glue *can* run on the sim, which
 * is the boundary that distinguishes our testable PhotoKit usage from the device-only parts.
 */
class PhotoKitSmokeTest {

    @Test
    fun authorization_status_is_answerable_on_the_simulator() {
        // PHAuthorizationStatus is a non-negative enum; the real assertion is "this did not trap".
        val status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
        assertTrue(status >= 0L)
    }

    @Test
    fun fetching_assets_returns_a_result_without_trapping() {
        // Unauthorized fetch returns an empty PHFetchResult rather than crashing.
        val assets = PHAsset.fetchAssetsWithOptions(null)
        assertTrue(assets.count >= 0uL)
    }
}
