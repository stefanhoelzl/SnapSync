package app.snapsync.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.eventcreation.CreationStatusSource
import app.snapsync.eventcreation.EventCreator
import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.status.DownloadStatusSource
import app.snapsync.status.SyncStatusSource
import app.snapsync.ui.StatusScreen
import kotlinx.coroutines.CoroutineScope

/**
 * The shared left pane both desktop harnesses reuse: construct a [StatusContainerHost] from the
 * injected seams, then render the real [StatusScreen] inside the [PhoneFrame]. The forge harness
 * ([app.snapsync.desktop.main]) supplies stand-in cells; the future full-stack world harness supplies
 * the real platform-agnostic stack — only the seam *sources* (and the right pane) differ.
 *
 * The leave/share edges are UI-only test equipment: `leave` uses the container's no-op default so the
 * confirm dialog is reviewable but inert, and [share] is passed in by the host (the forge copies the
 * invite URL to the clipboard and logs it). Exercises the UI flow only; mutates no harness state.
 */
@Composable
fun StatusPane(
    syncSource: SyncStatusSource,
    permissionSource: PermissionStatusSource,
    requester: PermissionRequester,
    configSource: ConfigSource,
    configStore: ConfigStore,
    creationStatusSource: CreationStatusSource,
    creator: EventCreator,
    downloadSource: DownloadStatusSource,
    share: (String) -> Unit,
    scope: CoroutineScope,
) {
    val host = remember {
        StatusContainerHost(
            syncSource,
            permissionSource,
            requester,
            configSource,
            configStore,
            scope,
            share = share,
            creationStatusSource = creationStatusSource,
            creator = creator,
            downloadSource = downloadSource,
        )
    }
    val state by host.container.stateFlow.collectAsState()
    val download by host.downloadStatus.collectAsState()
    // The joined-layer presets force a canned event, so this is non-null there → the QR renders.
    val inviteUrl by host.inviteUrl.collectAsState()

    PhoneFrame {
        // The container's `leave` defaults to a no-op (no leave fake wired), so the dialog is
        // reviewable but Confirm is inert — the harness exercises UI only. Share is a clipboard/log
        // stub; the QR renders from the canned invite URL.
        StatusScreen(
            state,
            host::onRequestPermission,
            host::onOpenSettings,
            onLeaveEvent = host::onLeaveEvent,
            onShareInvite = host::onShareInvite,
            inviteUrl = inviteUrl,
            onCreateEvent = host::onCreateEvent,
            downloadedCount = download.downloaded,
            downloadTotal = download.total,
        )
    }
}
