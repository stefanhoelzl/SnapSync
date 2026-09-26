package app.snapsync.ios.upload

import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJobState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.setValue
import platform.Photos.PHAssetResourceUploadJobStateCancelled
import platform.Photos.PHAssetResourceUploadJobStateFailed
import platform.Photos.PHAssetResourceUploadJobStatePending
import platform.Photos.PHAssetResourceUploadJobStateRegistered
import platform.Photos.PHAssetResourceUploadJobStateSucceeded
import platform.Photos.PHPhotosErrorInvalidResource
import platform.Photos.PHPhotosErrorLimitExceeded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The PhotoKit upload-job vocabulary mappings (capability `background-upload`). What a job then MEANS for the ledger is
 * the upload services', tested there (`UploadJobDecisionsTest`).
 *
 * Every assertion names the SDK's **own constants** rather than the integers behind them — the lesson
 * `PhotoKitResourceRoleTest` records: a table over Apple's ABI asserted as bare integers against bare integers is
 * indistinguishable from arithmetic.
 *
 * Two tests below pass `null` where cinterop declares the value non-null. **That is the point.** A job's `destination`
 * is declared non-null and is nil at runtime, which cost an on-device bug (`8c8dbe28`). Because these calls exist,
 * narrowing the parameters back to the type cinterop claims **stops compiling** — that compile error is the guard.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class PhotoKitJobMappingTest {

    private fun request(url: String): NSURLRequest = NSURLRequest.requestWithURL(NSURL.URLWithString(url)!!)

    private fun request(url: String, contentType: String): NSURLRequest =
        NSMutableURLRequest(uRL = NSURL.URLWithString(url)!!).apply { setValue(contentType, forHTTPHeaderField = "Content-Type") }

    private fun nsError(domain: String, code: Long): NSError = NSError.errorWithDomain(domain, code, userInfo = null)

    @Test
    fun `every declared job state maps to its platform-neutral state`() {
        assertEquals(UploadJobState.REGISTERED, photoKitJobState(PHAssetResourceUploadJobStateRegistered))
        assertEquals(UploadJobState.PENDING, photoKitJobState(PHAssetResourceUploadJobStatePending))
        assertEquals(UploadJobState.FAILED, photoKitJobState(PHAssetResourceUploadJobStateFailed))
        assertEquals(UploadJobState.SUCCEEDED, photoKitJobState(PHAssetResourceUploadJobStateSucceeded))
        assertEquals(UploadJobState.CANCELLED, photoKitJobState(PHAssetResourceUploadJobStateCancelled))
    }

    /** A value no SDK header carries is UNKNOWN — kept apart from PENDING since phase 11f, so a guess reads as one. */
    @Test
    fun `an undeclared state is unknown and never mistaken for pending`() {
        assertEquals(UploadJobState.UNKNOWN, photoKitJobState(9_999L))
    }

    @Test
    fun `a destination yields its path whatever its last segment`() {
        // Under the v2 route the last segment is the ROLE; nothing is read out of it.
        assertEquals(
            "/api/v2/files/devices/D/ABC-123/primary",
            photoKitDestinationPath(request("https://edge.example/api/v2/files/devices/D/ABC-123/primary?filename=IMG_1.HEIC")),
        )
    }

    /** THE GUARD (see the class KDoc): if this stops compiling, restore the nullable parameter; never delete it. */
    @Test
    fun `a job with no destination yields no path`() {
        assertNull(photoKitDestinationPath(null))
    }

    @Test
    fun `the content type is the one the job's stored destination carries`() {
        assertEquals("image/heic", request("https://edge.example/f/k-primary.heic", "image/heic").contentTypeHeader())
    }

    /** HTTP header names are case-insensitive, and the OS returns them as it stored them, not as we spelled them. */
    @Test
    fun `the destination header is matched case-insensitively`() {
        val destination = NSMutableURLRequest(uRL = NSURL.URLWithString("https://edge.example/f/k-primary.heic")!!)
            .apply { setValue("image/jpeg", forHTTPHeaderField = "content-type") }
        assertEquals("image/jpeg", destination.contentTypeHeader())
    }

    @Test
    fun `a missing or blank header is no answer`() {
        val none: NSURLRequest? = null
        assertNull(none.contentTypeHeader())
        assertNull(request("https://edge.example/f/k-primary.heic").contentTypeHeader())
        assertNull(request("https://edge.example/f/k-primary.heic", "  ").contentTypeHeader())
    }

    @Test
    fun `no error means the job was created`() {
        assertEquals(UploadCreateOutcome.CREATED, createResultFor(null))
    }

    @Test
    fun `the in-flight job cap is distinguished from an outright failure`() {
        assertEquals(UploadCreateOutcome.LIMIT_EXCEEDED, createResultFor(PHPhotosErrorLimitExceeded))
        assertEquals(UploadCreateOutcome.FAILED, createResultFor(PHPhotosErrorInvalidResource))
    }

    /** The exact string is what the device log and the diagnostic dump carry, so it is pinned. */
    @Test
    fun `an NSError flattens to its domain and code`() {
        assertEquals(
            UploadError.Unknown("PHPhotosErrorDomain:3307"),
            photoKitUploadError(nsError("PHPhotosErrorDomain", PHPhotosErrorLimitExceeded)),
        )
    }
}
