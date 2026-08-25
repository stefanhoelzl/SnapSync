package app.snapsync.ios.upload

import app.snapsync.model.UploadError
import app.snapsync.ports.CreateResult
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSURLRequest
// Kotlin/Native exposes this ObjC member as an extension, so it needs an explicit import (the same
// shape as `platform.Foundation.setValue` in IosDiscovery).
import platform.Foundation.allHTTPHeaderFields
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceUploadJobState
import platform.Photos.PHAssetResourceUploadJobStateCancelled
import platform.Photos.PHAssetResourceUploadJobStateFailed
import platform.Photos.PHAssetResourceUploadJobStatePending
import platform.Photos.PHAssetResourceUploadJobStateRegistered
import platform.Photos.PHAssetResourceUploadJobStateSucceeded
import platform.Photos.PHPhotosErrorLimitExceeded

/**
 * The PhotoKit upload-job **vocabulary mappings** — every decision `IosPhotoKitUploadPlatform` makes
 * that is not an OS effect, extracted so it can be exercised without a `PHAssetResourceUploadJob`
 * (which has no public initializer and only ever arrives from a fetch, so no host can construct one).
 *
 * These live here, in the adapter, rather than in `:domain` — deliberately. A platform's magic values,
 * ABI integers and error-domain tables SHALL NOT appear in `model/`/`ports/`/`feature/` "even where the
 * platform-free zones are the cheaper place to unit-test them" (spec `module-architecture`, "Ports are
 * the I/O boundary named for the need"), and this project has already made and reversed that mistake
 * once: see `PhotoKitResourceRoleTest`'s KDoc on a table of Apple's ABI that had been asserted in
 * `commonTest` as bare integers against bare integers. The tests beside this file name the SDK's own
 * constants, so a value moving under us is a red test rather than a silent mis-mapping.
 *
 * ## Why two parameters are nullable when cinterop says they are not
 *
 * `PHAssetResourceUploadJob.destination` and `.resource` are declared **non-null** by the Kotlin/Native
 * Photos klib and are **nil at runtime** — `destination` for some job states, `resource` for every
 * succeeded job (the system releases it after upload). Both facts were paid for on device:
 *
 * - `05435ff9` — `EXC_BAD_ACCESS` crash-loop before any work: the drain dereferenced `resource`.
 * - `8c8dbe28` — system error 50008, stalled at "sync in progress": succeeded jobs were skipped and
 *   never acknowledged, because the ledger key was read from `resource` instead of `destination`.
 *
 * A Kotlin null check against a non-null-typed value may be elided; against a nullable parameter it
 * cannot. So the widening happens **once, at these named boundaries**, instead of in a local variable
 * that reads like a redundant `?` someone will tidy away. The tests call each of these with `null`,
 * which means narrowing a parameter to match cinterop's claim **stops compiling** — that compile error
 * is the guard, and it is why these signatures must not be "corrected".
 *
 * Decision record: `changes/archive/2026-08-09-extract-upload-platform-mappings` (design D2).
 */

/**
 * Terminal-ish state of a returned system job, mirroring `PHAssetResourceUploadJobState`.
 *
 * It lives here rather than on the shared `BackgroundTransfer` port because this is now its only reader:
 * terminal facts no longer cross that seam (the adapter records them into the ledger and acknowledges in
 * place), so a platform-neutral job state had no one left to be neutral for. It is PhotoKit vocabulary,
 * and an adapter is exactly where technology vocabulary belongs.
 */
enum class PhotoKitJobState { SUCCEEDED, FAILED, CANCELLED, PENDING, REGISTERED }

/** The disposition of one fetched system job, before the loop attaches what cannot leave it. */
sealed interface FetchedJob {

    /** The job maps to a ledger key and should be surfaced to the cycle. */
    data class Emit(val key: String, val state: PhotoKitJobState, val error: UploadError?) : FetchedJob

    /**
     * The job carries no recoverable ledger key. It is **still acknowledged**: every presented job must
     * be acknowledged or the system reports `appex failed to acknowledge jobs for processing state`
     * (error 50008) — the failure `8c8dbe28` fixed.
     */
    data object AcknowledgeToDrain : FetchedJob
}

/**
 * Classify one fetched system upload job.
 *
 * The ledger key is the destination URL's **last path segment** — the only field reliably present for
 * every job state, since `resource` is nil once a job has succeeded (capability `ios-photokit-upload`,
 * "Completion and retry adjudication"). [destination] is nullable on purpose; see this file's KDoc.
 */
@OptIn(ExperimentalForeignApi::class)
fun classifyPhotoKitJob(
    destination: NSURLRequest?,
    state: PHAssetResourceUploadJobState,
    error: NSError?,
): FetchedJob {
    val key = destination?.URL?.lastPathComponent ?: return FetchedJob.AcknowledgeToDrain
    return FetchedJob.Emit(
        key = key,
        state = photoKitJobState(state),
        error = error?.let { photoKitUploadError(it) },
    )
}

/**
 * `PHAssetResourceUploadJobState` → the platform-neutral [PhotoKitJobState].
 *
 * All five states the SDK declares are named. The `else` therefore means exactly one thing — a value no
 * SDK header carries — rather than doubling as the arm that handles `Pending`. It maps to
 * [PhotoKitJobState.PENDING], which the terminal-job drain adjudicates as a retry-spent failure: the
 * key is recorded `FAILED`, a fresh job is created if the resource survives, and the job is
 * acknowledged either way. That is safe (the edge PUT is idempotent and keys are deterministic, so a
 * re-send overwrites the same object) but it is a guess, which is why the declared set is pinned at
 * build time by `:test:architecture`'s platform-vocabulary pin — a case Apple adds fails the
 * Kotlin/Native bump rather than reaching a device untaught.
 *
 * The `else` cannot be removed: cinterop renders `NS_ENUM` as a typealias over `NSInteger` plus loose
 * constants, never a Kotlin `enum class`, so a `when` over one can never be compiler-exhaustive.
 */
fun photoKitJobState(state: PHAssetResourceUploadJobState): PhotoKitJobState = when (state) {
    PHAssetResourceUploadJobStateSucceeded -> PhotoKitJobState.SUCCEEDED
    PHAssetResourceUploadJobStateFailed -> PhotoKitJobState.FAILED
    PHAssetResourceUploadJobStateCancelled -> PhotoKitJobState.CANCELLED
    PHAssetResourceUploadJobStateRegistered -> PhotoKitJobState.REGISTERED
    PHAssetResourceUploadJobStatePending -> PhotoKitJobState.PENDING
    else -> PhotoKitJobState.PENDING
}

/**
 * The content type to report for a fetched job — the type the job was **created with**, read back from
 * its own stored destination header. That is the same field [classifyPhotoKitJob] recovers the ledger
 * key from, and for the same reason: it is the one field present in every job state.
 *
 * Deriving it from [resource] alone was silently wrong. [resource] is nullable on purpose (see this
 * file's KDoc) — it is nil for every succeeded job, and dereferencing it is what crash-looped the
 * extension in `05435ff9` — so a retried upload, whose `Resource` the cycle rebuilds from the key alone,
 * fell through to `application/octet-stream`, and every object that had ever failed once was stored with
 * that type. Measured on device (2026-08-07, SE2 / iOS 26.6) that the destination's headers survive the
 * system's job store on both the `.retry` and `.acknowledge` sets; re-measure if the tier moves to the
 * iOS 27 `PHBackgroundResourceUploadJobExtension`.
 *
 * The resource's own identifier remains the second answer, and `application/octet-stream` the third —
 * reached only when neither source carries one, for which it is the correct answer: the bytes are
 * already uploaded and the value is only carried alongside a terminal outcome.
 */
@OptIn(ExperimentalForeignApi::class)
fun photoKitContentType(destination: NSURLRequest?, resource: PHAssetResource?): String =
    destination.contentTypeHeader()
        ?: resource?.uniformTypeIdentifier
        ?: "application/octet-stream"

/**
 * The `Content-Type` a stored destination carries, or null when it carries none.
 *
 * Case-insensitive because HTTP header names are, and `allHTTPHeaderFields` returns them as the OS
 * stored them rather than as we spelled them. Blank is treated as absent — a header that says nothing is
 * not an answer, and the caller has real fallbacks.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSURLRequest?.contentTypeHeader(): String? {
    val headers = this?.allHTTPHeaderFields ?: return null
    val value = headers.entries
        .firstOrNull { (it.key as? String)?.equals("Content-Type", ignoreCase = true) == true }
        ?.value as? String
    return value?.takeIf { it.isNotBlank() }
}

/**
 * The outcome of `creationRequestForJobWithDestination`, from the error it reported (`null` = created).
 *
 * `PHPhotosErrorLimitExceeded` is the system's in-flight job cap — the cycle defers the remainder and
 * asks to be re-invoked. Any other error means the job was **not** created, so the caller must not
 * record a `REQUESTED` row for a job that does not exist (capability `ios-photokit-upload`,
 * "Cap-aware creation and tri-state processing result").
 */
fun createResultFor(errorCode: Long?): CreateResult = when (errorCode) {
    null -> CreateResult.CREATED
    PHPhotosErrorLimitExceeded -> CreateResult.LIMIT_EXCEEDED
    else -> CreateResult.FAILED
}

/**
 * `NSError` → [UploadError]. Deliberately flattened to [UploadError.Unknown]: v1 retries forever and
 * nothing branches on the variant (see `UploadError`'s own KDoc), so the taxonomy would buy no
 * behaviour here. The exact `"domain:code"` string is what the device logs and diagnostic dumps carry,
 * so it is pinned by test rather than left incidental.
 */
@OptIn(ExperimentalForeignApi::class)
fun photoKitUploadError(error: NSError): UploadError =
    UploadError.Unknown("${error.domain}:${error.code}")
