package app.snapsync.desktop

import app.snapsync.sync.SyncStatus
import app.snapsync.sync.SyncStatusSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The single mutation path for the harness's stand-in sync state: every display-override
 * button goes through a named method here, never an inline mutation in a composable.
 */
class PanelController {
    private val state = MutableStateFlow(SyncStatus(pending = 0, completed = 0))

    val source: SyncStatusSource = object : SyncStatusSource {
        override val status = state
    }

    fun showIdle() {
        state.value = SyncStatus(pending = 0, completed = 0)
    }

    fun showUploading(done: Int, total: Int) {
        require(done in 0 until total) { "done must be in 0 until total" }
        state.value = SyncStatus(pending = total - done, completed = done)
    }
}
