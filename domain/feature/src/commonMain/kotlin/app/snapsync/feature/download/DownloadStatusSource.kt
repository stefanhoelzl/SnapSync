package app.snapsync.feature.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Download progress feeding the joined-layer status line's download direction (capability
 * `photo-download`): [downloaded] foreign assets imported of [total] foreign assets known for this
 * event, plus [inFlight] — foreign assets with a resource download **sent to the OS but not yet
 * staged** (the download analogue of `SyncProgress.pending`). [inFlight] is display-only: it drives
 * only the download arrow's pulse (live activity), never the `downloaded`/`total` completeness notion.
 * Independent of the own-device upload status.
 */
data class DownloadProgress(
    val downloaded: Int,
    val total: Int,
    val inFlight: Int = 0,
    /** Whether these counts came from a refresh at all. See [UNREAD]. */
    val read: Boolean = true,
) {
    /** Nothing foreign to collect yet (the download direction is settled). */
    val isEmpty: Boolean get() = total == 0

    companion object {
        /**
         * The value before any successful refresh: **un-read**, not an event with nothing to receive.
         *
         * This distinction is not symmetry for its own sake. The joined screen's direction arrows are
         * **conjunctive** — "In sync" is shown exactly when BOTH are hidden (`sync-status-screen`) — and
         * the download arrow hides when `downloaded >= total`. A placeholder `(0, 0)` satisfies that,
         * so an un-read download projection can carry the whole screen to a settled checkmark on its
         * own, even after the upload side learned to distinguish un-read from zero. Without this the
         * defect would simply relocate to the other arm (`SNAPSYNC-14`, `SNAPSYNC-16`).
         */
        val UNREAD = DownloadProgress(downloaded = 0, total = 0, inFlight = 0, read = false)
    }
}

/**
 * The seam the presentation container reads for download progress. A `StateFlow` so the screen has a
 * synchronous current value; [refresh] re-reads the counts (foreground entry / after a reconcile or
 * import). The real impl reads the download store; the in-memory impl backs the desktop harness/tests.
 */
interface DownloadStatusSource {
    val progress: StateFlow<DownloadProgress>
    suspend fun refresh()
}

/**
 * A settable in-memory [DownloadStatusSource] for the harness/tests and the default (inert) wiring.
 *
 * Defaults to [DownloadProgress.UNREAD], so a caller that states no progress gets the honest
 * "nothing has been read" rather than a counted-empty union that settles the download arrow.
 */
class InMemoryDownloadStatusSource(initial: DownloadProgress = DownloadProgress.UNREAD) : DownloadStatusSource {
    private val _progress = MutableStateFlow(initial)
    override val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()
    override suspend fun refresh() = Unit
    fun set(value: DownloadProgress) { _progress.value = value }
}
