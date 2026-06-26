package app.snapsync.ios

import app.snapsync.status.ObservedCompletionsSource
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSURLRequest
import platform.Photos.PHAssetResourceUploadJob
import platform.Photos.PHAssetResourceUploadJobActionAcknowledge
import platform.Photos.PHAssetResourceUploadJobStateSucceeded

/**
 * The iOS [ObservedCompletionsSource]: reads the system's upload jobs **from the app process** (the
 * spike proved this is callable and side-effect-free) and reports the keys of those the system has
 * marked `succeeded` but the extension has not yet acknowledged — the completions the ledger does not
 * yet know about. [refresh] re-reads and replaces [keys]; accumulation/retention is the projection's
 * job (the sticky overlay in `:domain:status`).
 *
 * Strictly **read-only**: it fetches and maps to keys, and NEVER acknowledges/retries a job — so it
 * cannot consume a job the extension must still acknowledge, and the extension stays the single ledger
 * writer. Mapping to the ledger key is the destination URL's last path segment, the same field the
 * extension's `IosUploadJobPlatform` uses (the only one present for every job state). Guarded by
 * [supported] so it is an empty-set no-op where the background-upload API is unavailable. The fetch
 * runs on the caller's dispatcher (the foreground poll, on Main) — proven on device by the spike — and
 * only a `Set<String>` escapes, no PhotoKit objects.
 */
@OptIn(ExperimentalForeignApi::class)
class IosObservedCompletionsSource(
    private val log: Logger,
    private val supported: () -> Boolean,
) : ObservedCompletionsSource {

    private val state = MutableStateFlow<Set<String>>(emptySet())
    override val keys: StateFlow<Set<String>> = state

    override suspend fun refresh() {
        if (!supported()) return
        state.value = fetchSucceededKeys()
    }

    private fun fetchSucceededKeys(): Set<String> {
        val jobs = PHAssetResourceUploadJob.fetchJobsWithAction(PHAssetResourceUploadJobActionAcknowledge, options = null)
        val out = mutableSetOf<String>()
        var index = 0uL
        while (index < jobs.count) {
            val job = jobs.objectAtIndex(index) as PHAssetResourceUploadJob
            index++
            if (job.state != PHAssetResourceUploadJobStateSucceeded) continue
            // `resource` is nil for succeeded jobs; the destination URL is the only reliable key source.
            val destination: NSURLRequest? = job.destination
            val key = destination?.URL?.lastPathComponent ?: continue
            out += key
        }
        log.i { "observed succeeded jobs: ${out.size}" }
        return out
    }
}
