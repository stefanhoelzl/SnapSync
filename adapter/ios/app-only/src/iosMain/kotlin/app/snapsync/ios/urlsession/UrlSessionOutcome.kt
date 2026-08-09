package app.snapsync.ios.urlsession

import app.snapsync.model.UploadError
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError

/**
 * The app-driven tier's **outcome decisions** — the `URLSession` counterparts of
 * `PhotoKitJobMapping.kt`, extracted for the same reason and kept symmetric with it deliberately.
 *
 * Tier asymmetry is this project's recurring failure mode: the rejoin reconciliation, the direction
 * gate and the membership read each shipped on one upload tier and not the other (see
 * `app/ios/CLAUDE.md`). A decision that is tested on the PhotoKit tier and welded to a delegate
 * callback on this one is the same asymmetry in a smaller form, so both tiers' decisions move out
 * together.
 *
 * What stays in `IosUrlSessionUploadPlatform`: the lock, the in-flight registry, byte staging, the
 * orphan sweep, and the session/delegate lifecycle. Their correctness is concurrency and filesystem
 * behaviour, which extraction does not make more provable.
 *
 * Decision record: `changes/archive/2026-08-09-extract-upload-platform-mappings` (design D3).
 */

/** What a delivered `URLSession` task completion means for the ledger. */
sealed interface TaskCompletion {

    /** The task carried its ledger key and reached a terminal outcome. */
    data class Record(val key: String, val success: Boolean, val error: UploadError?) : TaskCompletion

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
    return TaskCompletion.Record(
        key = key,
        success = success,
        error = if (success) null else urlSessionUploadError(statusCode, error),
    )
}

/**
 * The failure detail for a non-successful task: the transport error when there was one, otherwise the
 * HTTP status. Flattened to [UploadError.Unknown] for the same reason as the PhotoKit tier's mapping —
 * v1 retries forever and nothing branches on the variant (see `UploadError`'s KDoc).
 */
@OptIn(ExperimentalForeignApi::class)
fun urlSessionUploadError(statusCode: Long, error: NSError?): UploadError =
    UploadError.Unknown(error?.let { "${it.domain}:${it.code}" } ?: "http:$statusCode")

/**
 * The ledger keys stranded since the last drain: `REQUESTED` in the ledger, with **no live task** and
 * **no completion delivered this round**. Each is surfaced as a terminal failure so the row flips
 * `REQUESTED`→`FAILED` and a later full enumeration re-uploads it — the precise replacement for the
 * PhotoKit tier's blanket `clearRequested`.
 *
 * This is why the tier recovers from process death at all: a task lost when the app was force-quit
 * leaves a `REQUESTED` row that the engine treats as in-flight and never re-issues, so without this
 * subtraction the photo is abandoned silently and permanently.
 *
 * Pure set arithmetic over `String` — no platform type reaches it, which is what makes the recovery
 * rule assertable without a session, a task, or a device.
 */
fun strandedKeys(pending: Set<String>, live: Set<String>, drained: Set<String>): List<String> =
    pending.filter { it !in live && it !in drained }
