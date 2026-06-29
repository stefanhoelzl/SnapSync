package app.snapsync.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The event's **complete assets** as read from storage truth — the completeness listing
 * (`GET /event/<id>/files`), which returns only assets all of whose manifest-named resources are
 * stored. [completed] is a level-triggered holder of the complete-asset id set (its `size` is the
 * displayed `completed` count; the set itself is used to prune in-flight manifests). [refresh]
 * re-reads the listing and replaces the value.
 *
 * The source is **observation-only** — it never uploads or mutates storage — and a failed listing
 * leaves the last good value in place rather than throwing, so a transient network error never
 * blanks the screen. It refreshes on **foreground entry** and on **each manifest `URLSession`
 * completion** (wired in the iOS composition root); there is no polling timer.
 */
interface CompletedAssetsSource {
    val completed: StateFlow<Set<String>>
    suspend fun refresh()
}

/**
 * A settable, in-memory [CompletedAssetsSource]: holds its truth synchronously and re-emits on
 * [set]. Used by the desktop harness and integration tests; the iOS app backs the seam with the
 * HTTP listing ([FilesCompletedAssetsSource]) instead. [refresh] is inert here (the fake's value is
 * driven directly).
 */
class MutableCompletedAssetsSource(initial: Set<String> = emptySet()) : CompletedAssetsSource {
    private val _completed = MutableStateFlow(initial)
    override val completed: StateFlow<Set<String>> = _completed.asStateFlow()

    override suspend fun refresh() = Unit

    fun set(assetIds: Set<String>) {
        _completed.value = assetIds
    }
}
