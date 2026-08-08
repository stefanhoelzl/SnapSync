package app.snapsync.ios.upload

import app.snapsync.model.UploadError
import app.snapsync.ports.CreateResult
import app.snapsync.ports.PlatformJobState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSMutableURLRequest
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
import kotlin.test.assertIs

/**
 * The PhotoKit upload-job vocabulary mappings (capability `ios-photokit-upload`).
 *
 * Every assertion names the SDK's **own constants** rather than the integers behind them — the lesson
 * `PhotoKitResourceRoleTest` records: a table over Apple's ABI asserted as bare integers against bare
 * integers is indistinguishable from arithmetic, and no JVM run and no gate can disagree with it.
 *
 * Two tests below pass `null` where cinterop declares the value non-null. **That is the point, not an
 * edge case.** `PHAssetResourceUploadJob.destination` and `.resource` are declared non-null and are nil
 * at runtime, and those nils cost two on-device bugs (`8c8dbe28`, `05435ff9`). Because these calls exist,
 * narrowing either parameter back to the type cinterop claims **stops compiling** — that compile error
 * is the real guard, and this file is what holds it in place.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class PhotoKitJobMappingTest {

    private fun request(url: String): NSURLRequest =
        NSURLRequest.requestWithURL(NSURL.URLWithString(url)!!)

    private fun request(url: String, contentType: String): NSURLRequest =
        NSMutableURLRequest(uRL = NSURL.URLWithString(url)!!).apply {
            setValue(contentType, forHTTPHeaderField = "Content-Type")
        }

    private fun nsError(domain: String, code: Long): NSError =
        NSError.errorWithDomain(domain, code, userInfo = null)

    // ---- photoKitJobState: all five declared states, by name -------------------------------------

    @Test
    fun `every declared job state maps to its platform state`() {
        assertEquals(PlatformJobState.REGISTERED, photoKitJobState(PHAssetResourceUploadJobStateRegistered))
        assertEquals(PlatformJobState.PENDING, photoKitJobState(PHAssetResourceUploadJobStatePending))
        assertEquals(PlatformJobState.FAILED, photoKitJobState(PHAssetResourceUploadJobStateFailed))
        assertEquals(PlatformJobState.SUCCEEDED, photoKitJobState(PHAssetResourceUploadJobStateSucceeded))
        assertEquals(PlatformJobState.CANCELLED, photoKitJobState(PHAssetResourceUploadJobStateCancelled))
    }

    /**
     * `Pending` is named explicitly, so the fallback arm means exactly one thing: a value no SDK header
     * carries. Both answer `PENDING` today, which is why this change is behaviour-preserving — but they
     * are different questions, and the declared set is pinned at build time so the second one stays
     * hypothetical.
     */
    @Test
    fun `an undeclared state falls back to pending rather than being mistaken for a known state`() {
        assertEquals(PlatformJobState.PENDING, photoKitJobState(PHAssetResourceUploadJobStatePending))
        assertEquals(PlatformJobState.PENDING, photoKitJobState(9_999L))
    }

    // ---- classifyPhotoKitJob ---------------------------------------------------------------------

    @Test
    fun `the ledger key is the destination URL's last path segment`() {
        val classified = classifyPhotoKitJob(
            destination = request("https://edge.example/api/v1/e/d/file/ABC-123-primary.heic"),
            state = PHAssetResourceUploadJobStateSucceeded,
            error = null,
        )
        val emit = assertIs<FetchedJob.Emit>(classified)
        assertEquals("ABC-123-primary.heic", emit.key)
        assertEquals(PlatformJobState.SUCCEEDED, emit.state)
        assertEquals(null, emit.error)
    }

    /**
     * THE GUARD (see the class KDoc). `destination` is nil for some job states even though cinterop
     * declares it non-null; a job whose key cannot be recovered must still be acknowledged, or the
     * system reports error 50008 and the tier stalls — exactly what `8c8dbe28` fixed.
     *
     * If this call stops compiling because someone narrowed the parameter, the fix is to restore the
     * nullable parameter, never to delete this test.
     */
    @Test
    fun `a job with no destination is drained rather than dropped`() {
        assertEquals(
            FetchedJob.AcknowledgeToDrain,
            classifyPhotoKitJob(
                destination = null,
                state = PHAssetResourceUploadJobStateSucceeded,
                error = null,
            ),
        )
    }

    @Test
    fun `a failed job carries its error through to the cycle`() {
        val classified = classifyPhotoKitJob(
            destination = request("https://edge.example/api/v1/e/d/file/k-primary.heic"),
            state = PHAssetResourceUploadJobStateFailed,
            error = nsError("PHPhotosErrorDomain", 3164L),
        )
        val emit = assertIs<FetchedJob.Emit>(classified)
        assertEquals(PlatformJobState.FAILED, emit.state)
        assertEquals(UploadError.Unknown("PHPhotosErrorDomain:3164"), emit.error)
    }

    // ---- photoKitContentType ---------------------------------------------------------------------

    /**
     * The type the job was created with, recovered from the destination the system stored — which is why
     * a **retried** upload keeps it. Before this, the type came from `resource` alone, which is nil once
     * a job succeeds, so every object that had ever failed once was stored `application/octet-stream`.
     */
    @Test
    fun `the content type is the one the job's stored destination carries`() {
        assertEquals(
            "image/heic",
            photoKitContentType(request("https://edge.example/f/k-primary.heic", "image/heic"), null),
        )
    }

    /** HTTP header names are case-insensitive, and the OS returns them as it stored them, not as we spelled them. */
    @Test
    fun `the destination header is matched case-insensitively`() {
        val destination = NSMutableURLRequest(
            uRL = NSURL.URLWithString("https://edge.example/f/k-primary.heic")!!,
        ).apply { setValue("image/jpeg", forHTTPHeaderField = "content-type") }
        assertEquals("image/jpeg", photoKitContentType(destination, null))
    }

    /**
     * THE OTHER GUARD. `resource` is nil for every succeeded job (the system releases it after upload),
     * and dereferencing it crash-looped the extension in `05435ff9`.
     *
     * Only this arm is reachable off-device: `PHAssetResource` has no public initializer, and an
     * unauthorised simulator has no asset to fetch one from. The middle arm — `uniformTypeIdentifier`,
     * which is honestly non-null — is verified on device.
     */
    @Test
    fun `a job with neither a typed destination nor a resource yields the octet-stream fallback`() {
        assertEquals("application/octet-stream", photoKitContentType(null, null))
        // A destination carrying no Content-Type, and one carrying a blank value, are both "no answer".
        assertEquals(
            "application/octet-stream",
            photoKitContentType(request("https://edge.example/f/k-primary.heic"), null),
        )
        assertEquals(
            "application/octet-stream",
            photoKitContentType(request("https://edge.example/f/k-primary.heic", "  "), null),
        )
    }

    // ---- createResultFor -------------------------------------------------------------------------

    @Test
    fun `no error means the job was created`() {
        assertEquals(CreateResult.CREATED, createResultFor(null))
    }

    @Test
    fun `the in-flight job cap is distinguished from an outright failure`() {
        assertEquals(CreateResult.LIMIT_EXCEEDED, createResultFor(PHPhotosErrorLimitExceeded))
        assertEquals(CreateResult.FAILED, createResultFor(PHPhotosErrorInvalidResource))
    }

    // ---- photoKitUploadError ---------------------------------------------------------------------

    /**
     * The exact string is what the device log and the diagnostic dump carry, so it is pinned rather
     * than left incidental. Deliberately flattened to [UploadError.Unknown]: v1 retries forever and
     * nothing branches on the variant.
     */
    @Test
    fun `an NSError flattens to its domain and code`() {
        assertEquals(
            UploadError.Unknown("PHPhotosErrorDomain:3307"),
            photoKitUploadError(nsError("PHPhotosErrorDomain", PHPhotosErrorLimitExceeded)),
        )
    }
}
