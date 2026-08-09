package app.snapsync.ios.upload

import app.snapsync.model.UploadError
import app.snapsync.ports.CreateResult
import app.snapsync.ports.PlatformJobState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSURLRequest
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

/** The disposition of one fetched system job, before the loop attaches what cannot leave it. */
sealed interface FetchedJob {

    /** The job maps to a ledger key and should be surfaced to the cycle. */
    data class Emit(val key: String, val state: PlatformJobState, val error: UploadError?) : FetchedJob

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
 * `PHAssetResourceUploadJobState` → the platform-neutral [PlatformJobState].
 *
 * All five states the SDK declares are named. The `else` therefore means exactly one thing — a value no
 * SDK header carries — rather than doubling as the arm that handles `Pending`. It maps to
 * [PlatformJobState.PENDING], which the terminal-job drain adjudicates as a retry-spent failure: the
 * key is recorded `FAILED`, a fresh job is created if the resource survives, and the job is
 * acknowledged either way. That is safe (the edge PUT is idempotent and keys are deterministic, so a
 * re-send overwrites the same object) but it is a guess, which is why the declared set is pinned at
 * build time by `:test:architecture`'s platform-vocabulary pin — a case Apple adds fails the
 * Kotlin/Native bump rather than reaching a device untaught.
 *
 * The `else` cannot be removed: cinterop renders `NS_ENUM` as a typealias over `NSInteger` plus loose
 * constants, never a Kotlin `enum class`, so a `when` over one can never be compiler-exhaustive.
 */
fun photoKitJobState(state: PHAssetResourceUploadJobState): PlatformJobState = when (state) {
    PHAssetResourceUploadJobStateSucceeded -> PlatformJobState.SUCCEEDED
    PHAssetResourceUploadJobStateFailed -> PlatformJobState.FAILED
    PHAssetResourceUploadJobStateCancelled -> PlatformJobState.CANCELLED
    PHAssetResourceUploadJobStateRegistered -> PlatformJobState.REGISTERED
    PHAssetResourceUploadJobStatePending -> PlatformJobState.PENDING
    else -> PlatformJobState.PENDING
}

/**
 * The content type to report for a fetched job's resource.
 *
 * [resource] is nullable on purpose (see this file's KDoc): it is nil for every succeeded job, and
 * dereferencing it is what crash-looped the extension in `05435ff9`. `uniformTypeIdentifier` itself is
 * honestly non-null, so this `?:` collapses exactly one cause — "the system already released the
 * resource" — for which `application/octet-stream` is the correct answer: the bytes are already
 * uploaded and the value is only carried alongside a terminal outcome.
 */
@OptIn(ExperimentalForeignApi::class)
fun photoKitContentType(resource: PHAssetResource?): String =
    resource?.uniformTypeIdentifier ?: "application/octet-stream"

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
