package app.snapsync.desktop

import app.snapsync.sync.SyncStatus
import app.snapsync.sync.SyncStatusSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The single mutation path for the harness's stand-in sync state: every display-override
 * button goes through a named method here, never an inline mutation in a composable.
 */
class PanelController(private val clock: Clock = Clock.System) {
    private val state = MutableStateFlow(NEVER_SYNCED)

    val source: SyncStatusSource = object : SyncStatusSource {
        override val status = state
    }

    fun showNeverSynced() {
        state.value = NEVER_SYNCED
    }

    fun showInProgress() {
        state.value = SyncStatus(
            pending = 22, completed = 12, failed = 0,
            active = true, estimatedRemaining = 2.minutes, lastFinishedAt = null,
        )
    }

    fun showInProgressEstimating() {
        state.value = SyncStatus(
            pending = 22, completed = 12, failed = 0,
            active = true, estimatedRemaining = null, lastFinishedAt = null,
        )
    }

    fun showSuspended() {
        state.value = SyncStatus(
            pending = 22, completed = 12, failed = 0,
            active = false, estimatedRemaining = null, lastFinishedAt = null,
        )
    }

    fun showComplete() {
        state.value = finishedPass(completed = 34, failed = 0)
    }

    fun showIncomplete() {
        state.value = finishedPass(completed = 31, failed = 3)
    }

    fun showFailed() {
        state.value = finishedPass(completed = 0, failed = 34)
    }

    private fun finishedPass(completed: Int, failed: Int) = SyncStatus(
        pending = 0, completed = completed, failed = failed,
        active = false, estimatedRemaining = null, lastFinishedAt = clock.now() - 5.minutes,
    )

    private companion object {
        val NEVER_SYNCED = SyncStatus(
            pending = 0, completed = 0, failed = 0,
            active = false, estimatedRemaining = null, lastFinishedAt = null,
        )
    }
}
