package app.snapsync.ios.upload

import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetResourceUploadJob
import platform.Photos.PHAssetResourceUploadJobActionAcknowledge
import platform.Photos.PHAssetResourceUploadJobActionRetry
import platform.Photos.PHPhotoLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A smoke test that the **PhotoKit surface `IosPhotoKitUploadPlatform` relies on** is available and
 * callable on the iOS simulator — it links, and the calls return rather than trap. It needs no photo
 * authorization: `authorizationStatus` is always answerable, and both `fetchAssets` and
 * `fetchJobsWithAction` return an empty result (not a trap) in the unauthorized state.
 *
 * It lives here rather than in `:app:ios:extension` because that is a wiring-only, untested module
 * (root `CLAUDE.md`) and the adapter it smoke-tests has lived in `:adapter:ios:ext-safe` since the
 * migration finale — the test was simply in the wrong module.
 *
 * **What is still device-only.** This file used to claim the whole background-upload-job subsystem was
 * simulator-unavailable. That was too strong: the *fetch* half is callable here, as
 * [fetching_upload_jobs_returns_an_empty_result_without_trapping] now asserts on every CI run
 * (measured by hand first — simulator, iOS 26.x / Xcode 26.6 / macOS 26.5.2, 2026-08-09 — and turned
 * into a standing assertion so it stops being an n=1 note). What remains unmeasured, and therefore
 * genuinely device-only, is job **creation** (`creationRequestForJobWithDestination`) and whether the
 * OS ever performs the upload. The cloud-identifier mapping stays out of scope for the same reason it
 * always was: it is iCloud-dependent.
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

    /**
     * Both fetch actions the adapter drives (`fetchRetryJobs` / `fetchAckJobs`) are callable here and
     * answer empty. A failure of this test is not a defect in our code — it falsifies the measurement
     * above, and the fix is to restore the device-only framing in this KDoc.
     */
    @Test
    fun fetching_upload_jobs_returns_an_empty_result_without_trapping() {
        val acknowledge = PHAssetResourceUploadJob.fetchJobsWithAction(
            PHAssetResourceUploadJobActionAcknowledge,
            options = null,
        )
        val retry = PHAssetResourceUploadJob.fetchJobsWithAction(
            PHAssetResourceUploadJobActionRetry,
            options = null,
        )
        assertEquals(0uL, acknowledge.count)
        assertEquals(0uL, retry.count)
    }
}
