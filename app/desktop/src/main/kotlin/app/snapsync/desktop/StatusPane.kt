package app.snapsync.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import app.snapsync.model.Direction
import app.snapsync.eventcreation.CreationStatusSource
import app.snapsync.eventcreation.EventCreator
import app.snapsync.ports.PermissionRequester
import app.snapsync.ports.PermissionStatusSource
import app.snapsync.presentation.AlwaysAttested
import app.snapsync.presentation.AttestedSource
import app.snapsync.presentation.JoinLoad
import app.snapsync.presentation.MutablePendingJoinSource
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.status.DownloadStatusSource
import app.snapsync.status.SyncStatusSource
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.components.LocalDarkThemeOverride
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
    commitJoin: suspend (String, String, String, String, Direction, Boolean) -> Boolean =
        { _, _, _, _, _, _ -> false },
    // Attestation health (capability `device-attestation`): defaulted to always-attested so the
    // full-stack harness constructs unchanged; the forge harness injects a MutableAttestedSource so
    // `SyncHealth.Unattested` is forgeable.
    attestedSource: AttestedSource = AlwaysAttested,
    // The join/switch overlay cell (capability `join-event`): defaulted to a fresh internal instance so
    // the full-stack harness's real gate drives it as before; the forge harness injects its own so any
    // `JoinPhase` is forgeable by writing this cell.
    pending: MutablePendingJoinSource = MutablePendingJoinSource(),
    // Exposes the constructed container so a harness's right pane can drive the gate (e.g. onOpenUrl).
    onHostReady: (StatusContainerHost) -> Unit = {},
    // Test-only theme override for the phone pane: `null` follows the (unreliable) desktop OS setting,
    // `true`/`false` forces dark/light so the real skin is reviewable without a device. Scoped to the
    // rendered `StatusScreen` only, so the harness's own control chrome is unaffected.
    darkThemeOverride: Boolean? = null,
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
            attestedSource = attestedSource,
            pending = pending,
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
        //
        // The theme override is provided around the real `StatusScreen` (which wraps itself in
        // `AppTheme`), so the harness toggle flips the phone pane's skin without touching its own chrome.
        CompositionLocalProvider(LocalDarkThemeOverride provides darkThemeOverride) {
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
}
