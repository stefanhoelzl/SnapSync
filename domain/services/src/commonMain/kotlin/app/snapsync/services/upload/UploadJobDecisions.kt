package app.snapsync.services.upload

import app.snapsync.model.TerminalOutcome
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJobState

/*
 * The per-job decisions over a platform's upload jobs (capability `background-upload`) — what the PhotoKit adapter
 * used to hold beside its vocabulary mappings (`PhotoKitJobMapping.kt`, decision record
 * `changes/archive/2026-08-09-extract-upload-platform-mappings`). They are the services' since phase 11f: a thin
 * `Upload` port reports job facts, and which row a job belongs to, what a terminal job means and which offered retry
 * is a key's are decided once here, for every platform, where `commonTest` reaches them.
 */

/** The disposition of one presented job, before the loop attaches what cannot leave it. */
sealed interface FetchedJob {

    /**
     * The job carries a destination this cycle can work from. [destinationPath] is what the ledger recorded when the
     * job was created, and is the only way back to the row. No key is read out of it: under the v2 byte route its last
     * segment is the resource's ROLE (`changes/retire-legacy-key-fallback`).
     */
    data class Emit(val destinationPath: String, val state: UploadJobState, val error: UploadError?) : FetchedJob

    /**
     * The job carries no recoverable destination. It is **still acknowledged**: every presented job must be, or the
     * PhotoKit queue reports `appex failed to acknowledge jobs for processing state` (error 50008) — `8c8dbe28`.
     */
    data object AcknowledgeToDrain : FetchedJob
}

/** Classify one presented job by the facts it carries: a destination path, or none. */
fun classifyFetchedJob(destinationPath: String?, state: UploadJobState, error: UploadError?): FetchedJob {
    val path = destinationPath ?: return FetchedJob.AcknowledgeToDrain
    return FetchedJob.Emit(destinationPath = path, state = state, error = error)
}

/** Whose row a presented job with a destination belongs to (capability `background-upload`). */
sealed interface JobRow {
    /** The row exists: settle it, re-create it, or retry it as the job's state says. */
    data class Found(val key: String) : JobRow

    /**
     * A byte-route job whose row is gone — an authoritative walk deleted it because the photo left the library or the
     * selection, possibly while the job was in flight. Answered quietly: acknowledged, nothing written, nothing handed
     * to the cycle (decision record `changes/selection-is-the-walk`, D3).
     */
    data object Pruned : JobRow

    /** A destination of no shape this build knows: an outcome discarded for a reason it cannot name — a fault. */
    data object Unmappable : JobRow
}

/**
 * Decide [JobRow] for a job whose destination is [path]: the row the ledger recorded for that destination
 * ([rowByDestination]); else [JobRow.Pruned] when the path is a byte route at all, and [JobRow.Unmappable] when it is
 * not. The recorded destination is the ONLY route: the v1 last-segment fallback is retired, so a v1-shaped job is
 * unmappable rather than pruned — its row may still exist and be `REQUESTED`, and a quiet prune would hide that.
 */
fun jobRowOf(path: String, rowByDestination: String?): JobRow = when {
    rowByDestination != null -> JobRow.Found(rowByDestination)
    isByteRoute(path) -> JobRow.Pruned
    else -> JobRow.Unmappable
}

/**
 * Whether [path] is the byte-route shape this build creates jobs for: the v2 identity-in-path
 * `/files/devices/<deviceId>/<assetId>/<role>`. The v1 `/files/devices/<deviceId>/<key>` is deliberately NOT one any
 * more — a job carrying it is unmappable, and reported (`changes/retire-legacy-key-fallback`, D2).
 */
fun isByteRoute(path: String): Boolean {
    val segments = path.split('/').filter { it.isNotEmpty() }
    val devices = segments.indexOf("devices")
    return devices > 0 && segments[devices - 1] == "files" && segments.size == devices + 4
}

/**
 * What a **terminal** job means: which ledger state to record, and whether the cycle must re-create it.
 *
 * A succeeded job becomes [TerminalOutcome.COMPLETED] directly: nothing a completion used to trigger is still owed
 * (`changes/retire-uploaded-state`). Every other state — `FAILED`, `CANCELLED`, `PENDING`, `REGISTERED`, and an
 * [UploadJobState.UNKNOWN] the platform reported untaught — returns its row to `DISCOVERED` and is re-created **iff its
 * resource is still live**: the edge PUT is idempotent and keys deterministic, so a re-send overwrites the same object.
 * [reCreate] is not "should retry": the platform's free retry happens before a job is terminal at all.
 */
data class TerminalDisposition(val outcome: TerminalOutcome, val reCreate: Boolean)

/** The pure adjudication every platform's presented terminal jobs get. */
fun terminalDisposition(state: UploadJobState, resourceIsLive: Boolean): TerminalDisposition {
    val succeeded = state == UploadJobState.SUCCEEDED
    return TerminalDisposition(
        outcome = if (succeeded) TerminalOutcome.COMPLETED else TerminalOutcome.FAILED,
        reCreate = !succeeded && resourceIsLive,
    )
}

/**
 * The content type to report for a presented job — the type it was **created with**, which the platform stored
 * ([storedType]); else the live resource's own ([resourceType]); else the generic `application/octet-stream`, reached
 * only when neither carries one (the bytes are already uploaded, and the value rides alongside a terminal outcome).
 * Deriving it from the resource alone was silently wrong: a succeeded job carries none, and every object that had ever
 * failed once was stored as `application/octet-stream` (measured SE2 / iOS 26.6, 2026-08-07).
 */
fun jobContentType(storedType: String?, resourceType: String?): String =
    storedType ?: resourceType ?: "application/octet-stream"

/**
 * The offered retry to re-point for [key]: the first candidate whose classified destination resolves to [key] through
 * [resolve] — the drain's own route, the recorded destination path — or null when none does. It never compares a
 * destination's last path segment to [key]: under the identity-in-path byte route that segment is the resource's role
 * and matches no key, which is how every free retry was once lost.
 */
suspend fun <J> retryJobMatching(
    candidates: List<Pair<J, FetchedJob>>,
    key: String,
    resolve: suspend (FetchedJob.Emit) -> String?,
): J? = candidates.firstOrNull { (_, classified) ->
    classified is FetchedJob.Emit && resolve(classified) == key
}?.first

/**
 * Whether [url] can be an upload job's destination: an `http` or `https` URL with a host. `NSURL.URLWithString` is no
 * guard — since iOS 17 it percent-encodes where it used to answer `nil`, so an empty string parses — so the services
 * refuse a destination that is not a URL before any platform sees it (`UploadContract`'s bad-destination clause).
 */
fun isUploadDestination(url: String): Boolean {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    val host = url.substringAfter("://", missingDelimiterValue = "").substringBefore('/').substringBefore('?')
    return (scheme == "http" || scheme == "https") && host.isNotEmpty()
}
