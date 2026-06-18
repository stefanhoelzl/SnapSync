package app.snapsync.desktop

import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.status.SyncStatus
import app.snapsync.status.SyncProgress
import app.snapsync.status.SyncStatusSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single mutation path for the harness's stand-in state: every display-override
 * button goes through a named method here, never an inline mutation in a composable.
 * Holds two cells (permission, sync) plus the armed request outcome, and implements
 * the stand-in sources and the fake [PermissionRequester].
 */
class PanelController(private val clock: Clock = Clock.System) {
    // The harness knows its truth synchronously, so it seeds Ready and never shows Loading.
    private val syncState = MutableStateFlow<SyncStatus>(SyncStatus.Ready(NEVER_SYNCED))
    private val permissionState = MutableStateFlow(PermissionStatus.NOT_DETERMINED)
    private val armedGrants = MutableStateFlow(true)

    val syncSource: SyncStatusSource = object : SyncStatusSource {
        override val status = syncState
    }

    val permissionSource: PermissionStatusSource = object : PermissionStatusSource {
        override val permission = permissionState
    }

    val requester: PermissionRequester = object : PermissionRequester {
        override fun request() {
            permissionState.value =
                if (armedGrants.value) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }

        override fun openSettings() {
            // The fake can't open anything: play "the user in Settings" with the
            // permission presets instead.
            println("openSettings() — simulate the Settings visit via the Permission presets")
        }
    }

    /** What the next gate-driven request() resolves to. */
    val armedRequestGrants = armedGrants.asStateFlow()

    fun armNextRequest(grants: Boolean) {
        armedGrants.value = grants
    }

    // Permission presets write the permission cell only: the sync cell is invisible behind
    // the gate, and an untouched forged sync state is what makes the revoked-and-restored
    // walk possible.

    fun showPermissionNotDetermined() {
        permissionState.value = PermissionStatus.NOT_DETERMINED
    }

    fun showPermissionDenied() {
        permissionState.value = PermissionStatus.DENIED
    }

    fun showPermissionGranted() {
        permissionState.value = PermissionStatus.GRANTED
    }

    // Loading has no SyncProgress payload, so it bypasses forgeSync; like the others it forces
    // Granted, since the reducer only surfaces Loading once permission is granted.
    fun showLoading() {
        permissionState.value = PermissionStatus.GRANTED
        syncState.value = SyncStatus.Loading
    }

    fun showNeverSynced() {
        forgeSync(NEVER_SYNCED)
    }

    fun showInProgress() {
        forgeSync(
            SyncProgress(
                pending = 22, completed = 12, failed = 0,
                active = true, estimatedRemaining = 2.minutes, lastFinishedAt = null,
            ),
        )
    }

    fun showInProgressEstimating() {
        forgeSync(
            SyncProgress(
                pending = 22, completed = 12, failed = 0,
                active = true, estimatedRemaining = null, lastFinishedAt = null,
            ),
        )
    }

    fun showSuspended() {
        forgeSync(
            SyncProgress(
                pending = 22, completed = 12, failed = 0,
                active = false, estimatedRemaining = null, lastFinishedAt = null,
            ),
        )
    }

    fun showComplete() {
        forgeSync(finishedPass(completed = 34, failed = 0))
    }

    fun showIncomplete() {
        forgeSync(finishedPass(completed = 31, failed = 3))
    }

    // A sync preset's intent is "show me this screen" — impossible while gated, so it
    // forces its precondition.
    private fun forgeSync(status: SyncProgress) {
        permissionState.value = PermissionStatus.GRANTED
        syncState.value = SyncStatus.Ready(status)
    }

    // Finished presets forge active = true: under suspended-first classification an inactive
    // snapshot is Suspended regardless of history.
    private fun finishedPass(completed: Int, failed: Int) = SyncProgress(
        pending = 0, completed = completed, failed = failed,
        active = true, estimatedRemaining = null, lastFinishedAt = clock.now() - 5.minutes,
    )

    private companion object {
        val NEVER_SYNCED = SyncProgress(
            pending = 0, completed = 0, failed = 0,
            active = true, estimatedRemaining = null, lastFinishedAt = null,
        )
    }
}
