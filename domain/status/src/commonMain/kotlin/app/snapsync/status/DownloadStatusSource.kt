package app.snapsync.status

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
data class DownloadProgress(val downloaded: Int, val total: Int, val inFlight: Int = 0) {
    /** Nothing foreign to collect yet (the download direction is settled). */
    val isEmpty: Boolean get() = total == 0
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

/** A settable in-memory [DownloadStatusSource] for the harness/tests and the default (inert) wiring. */
class InMemoryDownloadStatusSource(initial: DownloadProgress = DownloadProgress(0, 0)) : DownloadStatusSource {
    private val _progress = MutableStateFlow(initial)
    override val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()
    override suspend fun refresh() = Unit
    fun set(value: DownloadProgress) { _progress.value = value }
}
