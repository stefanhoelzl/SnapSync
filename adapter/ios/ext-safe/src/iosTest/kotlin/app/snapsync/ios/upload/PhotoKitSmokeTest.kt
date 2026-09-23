package app.snapsync.ios.upload

import platform.Photos.PHAssetResourceUploadJob
import platform.Photos.PHAssetResourceUploadJobActionAcknowledge
import platform.Photos.PHAssetResourceUploadJobActionRetry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A smoke test that the **upload-job fetches `IosPhotoKitUploadPlatform` relies on** are callable on the iOS
 * simulator: they link, and return rather than trap, with no photo authorization.
 *
 * This used to smoke-test the library reads as well (the authorization status, an unauthorized asset fetch).
 * Those are now contract clauses run against the real adapters on this host and in the simulator app
 * (capability `port-contracts`: `PhotoAccessContract`, `UploadDiscoveryContract`, `CandidateSourceContract`),
 * which assert outcomes rather than the absence of a trap.
 *
 * What remains is the upload-job subsystem, which no contract binds yet. It runs in the device extension
 * process, a host `port-contracts` lists as unbound, and on a simulator job **creation** terminates the
 * process. **Successor:** the upload-job phase carved out of `photokit-contracts`, which is expected to
 * replace this with a `BackgroundTransfer` contract.
 *
 * The fetch half was measured by hand first (simulator, iOS 26.x / Xcode 26.6 / macOS 26.5.2, 2026-08-09) and
 * turned into a standing assertion so it stops being an n=1 note. What stays genuinely device-only is job
 * **creation** (`creationRequestForJobWithDestination`) and whether the OS ever performs the upload.
 */
class PhotoKitSmokeTest {

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
