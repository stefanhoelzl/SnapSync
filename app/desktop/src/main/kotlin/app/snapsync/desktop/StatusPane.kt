package app.snapsync.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.Direction
import app.snapsync.eventcreation.CreationStatusSource
import app.snapsync.eventcreation.EventCreator
import app.snapsync.permission.PermissionRequester
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.presentation.JoinLoad
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
 * The share edge is UI-only test equipment ([share] is passed in by the host; the forge copies the
 * invite URL to the clipboard and logs it). [leave] defaults to the container's no-op, so the forge's
 * confirm dialog is reviewable but inert; the full-stack world harness passes the real `World.leave()`
 * edge so a confirmed leave actually runs the stack (imports retained, join cleared).
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
    leave: suspend () -> Unit = {},
    // The join-gate hooks (capability `join-event`): the forge leaves them inert (the join UI is
    // reviewable when a JoiningEvent state is forged), the full-stack world harness binds them to a
    // real `JoinEvent` over the world so `:app:desktop:run` drives the actual gate.
    loadJoinDetails: suspend (String) -> JoinLoad = { JoinLoad.Failed },
    commitJoin: suspend (String, String, String, Direction, Boolean) -> Boolean = { _, _, _, _, _ -> false },
    // Exposes the constructed container so a harness's right pane can drive the gate (e.g. onOpenUrl).
    onHostReady: (StatusContainerHost) -> Unit = {},
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
            leave = leave,
            creationStatusSource = creationStatusSource,
            creator = creator,
            loadJoinDetails = loadJoinDetails,
            commitJoin = commitJoin,
            downloadSource = downloadSource,
        ).also(onHostReady)
    }
    val state by host.container.stateFlow.collectAsState()
    // The joined-layer presets force a canned event, so this is non-null there → the QR renders.
    val inviteUrl by host.inviteUrl.collectAsState()
    val eventName by host.eventName.collectAsState()

    PhoneFrame {
        // `leave` is the injected edge: the forge leaves it defaulted (Confirm reviewable but inert),
        // the full-stack world harness binds it to `World.leave()`. Share is a clipboard/log stub; the
        // QR renders from the canned invite URL. Download progress now folds into the status line's
        // arrows via the reduction, so no separate download line is passed.
        StatusScreen(
            state,
            host::onRequestPermission,
            host::onOpenSettings,
            onLeaveEvent = host::onLeaveEvent,
            onShareInvite = host::onShareInvite,
            inviteUrl = inviteUrl,
            eventName = eventName,
            onCreateEvent = host::onCreateEvent,
            onConfirmJoin = host::onConfirmJoin,
            onAcknowledgeAccess = host::onAcknowledgeAccess,
            onCancelJoin = host::onCancelJoin,
            onRetryLoad = host::onRetryLoad,
            onRetryJoin = host::onRetryJoin,
            onConfirmSwitch = host::onConfirmSwitch,
            onCancelSwitch = host::onCancelSwitch,
        )
    }
}
