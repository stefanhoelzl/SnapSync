package app.snapsync.ios.urlsession

import app.snapsync.ports.TransferFailure
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError

/**
 * The app-driven tier's **outcome vocabulary** — how a delivered `URLSession` completion reads as an upload job's
 * terminal state, the counterpart of `PhotoKitJobMapping.kt`'s state mapping. What that state then means for the ledger
 * is the upload services' (phase 11f).
 *
 * Tier asymmetry is this project's recurring failure mode: the rejoin reconciliation, the direction
 * gate and the membership read each shipped on one upload tier and not the other (see
 * `docs/architecture.md` §11). A decision that is tested on the PhotoKit tier and welded to a delegate
 * callback on this one is the same asymmetry in a smaller form, so both tiers' decisions move out
 * together.
 *
 * What stays in `IosUrlSessionUploadPlatform`: the lock, the in-flight registry, byte staging, the
 * orphan sweep, and the session/delegate lifecycle. Their correctness is concurrency and filesystem
 * behaviour, which extraction does not make more provable.
 *
 * Decision record: `changes/archive/2026-08-09-extract-upload-platform-mappings` (design D3).
 */

/** What a delivered `URLSession` task completion reports. */
sealed interface TaskCompletion {

    /** The task carried its ledger key and reached a terminal outcome: a success when [error] is `null`. */
    data class Record(val key: String, val error: TransferFailure?) : TaskCompletion

    /**
     * The task carried no `taskDescription`, so it maps to no ledger key and nothing can be recorded.
     * A distinct case rather than a silent `return`: "this upload finished" and "a task finished and
     * we cannot say which" have different consequences, and the caller logs the second.
     */
    data object NoLedgerKey : TaskCompletion
}

/**
 * Classify one delivered task completion.
 *
 * Success is an absent transport error **and** a 2xx status — a `URLSession` task that receives a 403
 * completes with `didCompleteWithError == nil`, so the status is what distinguishes an upload the edge
 * accepted from one it rejected. [statusCode] is `0` when the response was not an
 * `NSHTTPURLResponse` (no response at all), which is not 2xx and therefore not a success.
 */
@OptIn(ExperimentalForeignApi::class)
fun classifyUrlSessionCompletion(
    taskDescription: String?,
    statusCode: Long,
    error: NSError?,
): TaskCompletion {
    val key = taskDescription ?: return TaskCompletion.NoLedgerKey
    val success = error == null && statusCode in 200L..299L
    return TaskCompletion.Record(key = key, error = if (success) null else urlSessionUploadFailure(statusCode, error))
}

/**
 * What a non-successful task's failure says: the transport error when there was one (its domain and code, nothing
 * more), otherwise the status the server answered (`http:<status>`). A status of `0` is no answer at all, which no
 * server gave. The exact text is what the device log and the diagnostic dump carry, so it is pinned by test.
 */
@OptIn(ExperimentalForeignApi::class)
fun urlSessionUploadFailure(statusCode: Long, error: NSError?): TransferFailure = when {
    error != null -> TransferFailure("${error.domain}:${error.code}")
    statusCode > 0L -> TransferFailure("http:${statusCode.toInt()}")
    else -> TransferFailure("no response")
}
