package app.snapsync.ios.upload

import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJobState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSURLRequest
// Kotlin/Native exposes this ObjC member as an extension, so it needs an explicit import (the same
// shape as `platform.Foundation.setValue` in uploadUrlRequest).
import platform.Foundation.allHTTPHeaderFields
import platform.Photos.PHAssetResourceUploadJobState
import platform.Photos.PHAssetResourceUploadJobStateCancelled
import platform.Photos.PHAssetResourceUploadJobStateFailed
import platform.Photos.PHAssetResourceUploadJobStatePending
import platform.Photos.PHAssetResourceUploadJobStateRegistered
import platform.Photos.PHAssetResourceUploadJobStateSucceeded
import platform.Photos.PHPhotosErrorLimitExceeded

/**
 * The PhotoKit upload-job **vocabulary mappings** — Apple's job states, error domain and limit code into the `Upload`
 * port's neutral vocabulary. What a job then MEANS for the ledger (its row, its terminal disposition, its offered
 * retry) moved to the upload services in phase 11f (`services/upload/UploadJobDecisions.kt`); what stays is exactly
 * what an adapter may hold: technology vocabulary, beside the SDK constants it names, so a value moving under us is a
 * red test (`PhotoKitJobMappingTest`) rather than a silent mis-mapping.
 *
 * ## Why two job fields are read as nullable when cinterop says they are not
 *
 * `PHAssetResourceUploadJob.destination` and `.resource` are declared **non-null** by the Kotlin/Native Photos klib
 * and are **nil at runtime** — `destination` for some job states, `resource` for every succeeded job. Both facts were
 * paid for on device (`05435ff9`: an `EXC_BAD_ACCESS` crash-loop; `8c8dbe28`: error 50008). A null check against a
 * non-null-typed value may be elided, so [SystemUploadJobApi] captures both as nullable locals and the mappings below
 * take nullable parameters — narrowing one to match cinterop's claim stops compiling, and that is the guard.
 *
 * Decision record: `changes/archive/2026-08-09-extract-upload-platform-mappings` (design D2).
 */

/**
 * `PHAssetResourceUploadJobState` → [UploadJobState].
 *
 * All five states the SDK declares are named. The `else` therefore means exactly one thing — a value no SDK header
 * carries — and maps to [UploadJobState.UNKNOWN], kept apart from `PENDING` since phase 11f so a guess reads as a
 * guess. The declared set is pinned at build time by `:test:architecture`'s platform-vocabulary pin, so a case Apple
 * adds fails the Kotlin/Native bump rather than reaching a device untaught. The `else` cannot be removed: cinterop
 * renders `NS_ENUM` as a typealias over `NSInteger`, never a Kotlin `enum class`.
 */
fun photoKitJobState(state: PHAssetResourceUploadJobState): UploadJobState = when (state) {
    PHAssetResourceUploadJobStateSucceeded -> UploadJobState.SUCCEEDED
    PHAssetResourceUploadJobStateFailed -> UploadJobState.FAILED
    PHAssetResourceUploadJobStateCancelled -> UploadJobState.CANCELLED
    PHAssetResourceUploadJobStateRegistered -> UploadJobState.REGISTERED
    PHAssetResourceUploadJobStatePending -> UploadJobState.PENDING
    else -> UploadJobState.UNKNOWN
}

/**
 * The path of the URL a job's stored destination names — what the upload services resolve its ledger row by — or null
 * when the OS answered no destination. [destination] is nullable on purpose (see this file's KDoc).
 */
@OptIn(ExperimentalForeignApi::class)
fun photoKitDestinationPath(destination: NSURLRequest?): String? = destination?.URL?.path

/**
 * The `Content-Type` a stored destination carries, or null when it carries none — the type the job was created with.
 * Measured on device (2026-08-07, SE2 / iOS 26.6) that the destination's headers survive the system's job store on
 * both the `.retry` and `.acknowledge` sets. Case-insensitive because HTTP header names are; blank is absent.
 */
@OptIn(ExperimentalForeignApi::class)
fun NSURLRequest?.contentTypeHeader(): String? {
    val headers = this?.allHTTPHeaderFields ?: return null
    val value = headers.entries
        .firstOrNull { (it.key as? String)?.equals("Content-Type", ignoreCase = true) == true }
        ?.value as? String
    return value?.takeIf { it.isNotBlank() }
}

/**
 * The outcome of `creationRequestForJobWithDestination`, from the error it reported (`null` = created).
 * `PHPhotosErrorLimitExceeded` is the system's in-flight job cap — the services defer the remainder. Any other error
 * means the job was **not** created.
 */
fun createResultFor(errorCode: Long?): UploadCreateOutcome = when (errorCode) {
    null -> UploadCreateOutcome.CREATED
    PHPhotosErrorLimitExceeded -> UploadCreateOutcome.LIMIT_EXCEEDED
    else -> UploadCreateOutcome.FAILED
}

/**
 * `NSError` → [UploadError]. Deliberately flattened to [UploadError.Unknown]: nothing branches on the variant, and the
 * exact `"domain:code"` string is what the device logs and diagnostic dumps carry, so it is pinned by test.
 */
@OptIn(ExperimentalForeignApi::class)
fun photoKitUploadError(error: NSError): UploadError =
    UploadError.Unknown("${error.domain}:${error.code}")
