package app.snapsync.desktop

import app.snapsync.model.EventStart
import app.snapsync.model.EventEnd
import app.snapsync.model.DeletesAt
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.snapsync.ports.ConfigSource
import app.snapsync.model.Direction
import app.snapsync.feature.creation.CreationStatusSource
import app.snapsync.feature.creation.EventCreator
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.model.JoinLoad
import app.snapsync.model.UserCommands
import app.snapsync.presentation.AlwaysAttested
import app.snapsync.presentation.AttestedSource
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.MutablePendingJoinSource
import app.snapsync.feature.membership.MutableRenameStatusSource
import app.snapsync.feature.membership.RenameStatusSource
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.feature.download.DownloadStatusSource
import app.snapsync.feature.status.SyncStatusSource
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.components.LocalDarkThemeOverride
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.TimeZone

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
    permissionSource: PhotoAccessStatusSource,
    requester: PhotoAccessRequester,
    configSource: ConfigSource,
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
    commitJoin: suspend (
        String, String, EventStart, EventEnd, DeletesAt, CaptureCutoff, CaptureCeiling, Direction, Boolean,
    ) -> Boolean = { _, _, _, _, _, _, _, _, _ -> false },
    // In-place membership reconfigure (capability `reconfigure-membership`): the forge leaves it inert
    // (the surface is reviewable, the command a no-op), the full-stack world harness binds it to the real
    // `world.core.userCommands.reconfigure` so `:app:desktop:run` drives the actual in-place rewrite.
    reconfigure: suspend (String, Direction, CaptureCutoff, CaptureCeiling, Boolean) -> Unit =
        { _, _, _, _, _ -> },
    // The heading rename (capability `event-rename`): the forge leaves it inert (the dialog is
    // reviewable, the command a no-op), the full-stack world harness binds the real
    // `world.core.userCommands.rename`/`resetRename` so `:app:desktop:run` drives the actual rewrite
    // against the world's mini-edge. The status source likewise defaults to an always-Idle instance.
    rename: (String, String) -> Unit = { _, _ -> },
    resetRename: () -> Unit = {},
    renameStatusSource: RenameStatusSource = MutableRenameStatusSource(),
    // The join-time shareable-count preview (capability `join-share-count`): the forge leaves it inert
    // (no count row), the full-stack world harness binds it to `world.core.loadShareableCount` so
    // `:app:desktop:run` shows the real count over the world gallery. Range-aware (`[cutoff, until]`).
    shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int? = { _, _ -> null },
    // The hidden bug-report affordance (capability `diagnostic-logging`): a double-tap on the app-name
    // label opens the sheet, and sending hands over what was written. The forge passes a UI-only stub
    // that echoes to the engine console (it composes no reporter); the full-stack world harness passes
    // the REAL `world.core.userCommands.sendDiagnostics`, so the sheet assembles a real dump over real
    // world state and the world's reporter records it. `null` — the default — wires no gesture at all,
    // which is the same structural rule a build with no reporting channel relies on.
    sendDiagnostics: (suspend (note: String, screen: String) -> Unit)? = null,
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
    // The cutoff formatter (migration step 9: the host/screen defaults died with the through-ports
    // repayment). Test equipment is exempt from the ports law, so the default binds the system
    // clock/zone directly — both harnesses review real wall-clock behavior, as before.
    cutoffFormatter: CutoffFormatter = CutoffFormatter(
        now = { Clock.System.now() },
        zone = TimeZone.currentSystemDefault(),
    ),
) {
    val host = remember {
        StatusContainerHost(
            syncSource,
            permissionSource.permission,
            configSource.config,
            scope,
            creationStatusSource = creationStatusSource,
            renameStatusSource = renameStatusSource,
            // The user-tap command bundle (spec `module-architecture`, "Commands cross one door"),
            // assembled from this pane's injected harness edges — the harness's stand-in for the
            // `compose/`-built production bundle (the world adopts `snapSyncApp` at step 10). The
            // permission taps bind the injected requester, mirroring `AppCore.userCommands`.
            commands = UserCommands(
                leave = leave,
                create = { name, startsAt, endsAt -> creator.create(name, startsAt.at.iso, endsAt.at.iso) },
                commitJoin = commitJoin,
                share = share,
                requestAccess = requester::request,
                openSettings = requester::openSettings,
                reconfigure = reconfigure,
                rename = rename,
                resetRename = resetRename,
                sendDiagnostics = sendDiagnostics,
            ),
            loadJoinDetails = loadJoinDetails,
            cutoffFormatter = cutoffFormatter,
            downloadSource = downloadSource,
            attestedSource = attestedSource,
            pending = pending,
        ).also(onHostReady)
    }
    val state by host.container.stateFlow.collectAsState()
    // The photo grant — the shareable-count row's recompute trigger (capability `join-share-count`).
    val photoPermission by permissionSource.permission.collectAsState()
    // The joined-layer presets force a canned event, so this is non-null there → the QR renders.
    val inviteUrl by host.inviteUrl.collectAsState()
    val eventName by host.eventName.collectAsState()
    // The current membership settings for the reconfigure surface (capability `reconfigure-membership`).
    val membership by host.membership.collectAsState()
    // The rename lifecycle for the heading's rename dialog (capability `event-rename`).
    val renameStatus by host.renameStatus.collectAsState()

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
            membership = membership,
            onReconfigure = host::onReconfigure,
            inviteUrl = inviteUrl,
            eventName = eventName,
            onCreateEvent = host::onCreateEvent,
            onConfirmJoin = host::onConfirmJoin,
            onAcknowledgeAccess = host::onAcknowledgeAccess,
            onChoosePhotos = host::onChoosePhotos,
            onCancelJoin = host::onCancelJoin,
            onRetryLoad = host::onRetryLoad,
            onRetryJoin = host::onRetryJoin,
            onConfirmSwitch = host::onConfirmSwitch,
            onCancelSwitch = host::onCancelSwitch,
            cutoff = cutoffFormatter,
            shareableCount = shareableCount,
            photoPermission = photoPermission,
            // The heading rename (capability `event-rename`).
            onRenameEvent = host::onRenameEvent,
            renameStatus = renameStatus,
            onRenameStatusConsumed = host::onRenameStatusConsumed,
            onSendDiagnostics = host.onSendDiagnostics,
        )
        }
    }
}
