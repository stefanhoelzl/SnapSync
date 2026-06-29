package app.snapsync.status

import app.snapsync.rejoin.EventFilesSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real [CompletedAssetsSource]: the complete-asset set obtained from the event's completeness
 * listing via the [EventFilesSource] (Change 1's `GET /event/<id>/files` seam — on iOS an HTTP
 * client against the compile-time device-facing host). [refresh] re-reads the listing and maps it to
 * the set of complete [assetId]s; [eventId] is read on each refresh so a (re)join switching events
 * lists the right one (no configured event → no-op, the value is left as is).
 *
 * A failed listing (network error, non-2xx — surfaced by [EventFilesSource] as a failed `Result`)
 * **keeps the last good value** rather than throwing to the status projection. Observation-only: it
 * never uploads or mutates storage.
 */
class FilesCompletedAssetsSource(
    private val files: EventFilesSource,
    private val eventId: () -> String?,
) : CompletedAssetsSource {

    private val _completed = MutableStateFlow<Set<String>>(emptySet())
    override val completed: StateFlow<Set<String>> = _completed.asStateFlow()

    override suspend fun refresh() {
        val id = eventId() ?: return
        files.list(id).onSuccess { assets ->
            _completed.value = assets.mapTo(mutableSetOf()) { it.assetId }
        }
        // On failure: keep the last good value (do nothing) — never throw to the projection.
    }
}
