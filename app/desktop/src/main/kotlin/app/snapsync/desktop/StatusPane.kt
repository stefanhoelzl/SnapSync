package app.snapsync.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import app.snapsync.ports.ConfigSource
import app.snapsync.feature.creation.CreationStatusSource
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.MutablePendingJoinSource
import app.snapsync.feature.membership.MutableRenameStatusSource
import app.snapsync.feature.membership.RenameStatusSource
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusSources
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.feature.download.DownloadStatusSource
import app.snapsync.feature.status.SyncStatusSource
import app.snapsync.ui.statusActions
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.statusActions
import app.snapsync.ui.components.LocalDarkThemeOverride
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The shared left pane both desktop harnesses reuse: construct a [StatusContainerHost] from the
 * injected seams, then render the real [StatusScreen] inside the [PhoneFrame]. The forge harness
 * ([app.snapsync.desktop.main]) supplies stand-in cells; the full-stack world harness supplies the real
 * platform-agnostic stack — only the seam *sources* (and the right pane) differ.
 *
 * It takes the whole [commands] and [queries] bundles, never a list of loose edges: it used to rebuild
 * `UserCommands` field by field from a dozen defaulted lambdas, and the rebuild silently dropped
 * `choosePhotos` and `openLink`, so both were dead in both harnesses. The world harness passes the world's
 * own bundles (decorated for its console); the forge passes its stand-ins, each stated.
 */
@Composable
fun StatusPane(
    syncSource: SyncStatusSource,
    permissionSource: PhotoAccessStatusSource,
    configSource: ConfigSource,
    creationStatusSource: CreationStatusSource,
    downloadSource: DownloadStatusSource,
    scope: CoroutineScope,
    commands: UserCommands,
    queries: UserQueries,
    renameStatusSource: RenameStatusSource = MutableRenameStatusSource(),
    // Attestation health (capability `device-attestation`): defaulted to always-attested so the
    // full-stack harness constructs unchanged; the forge harness injects its own cell so
    // `SyncHealth.Unattested` is forgeable.
    attested: StateFlow<Boolean> = MutableStateFlow(true),
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
            // Every read-model the reduction observes, in one bundle (`StatusSources`).
            StatusSources(
                sync = syncSource,
                permission = permissionSource.permission,
                config = configSource.config,
                creation = creationStatusSource,
                rename = renameStatusSource,
                download = downloadSource,
                attested = attested,
                pending = pending,
            ),
            scope = scope,
            commands = commands,
            queries = queries,
            cutoffFormatter = cutoffFormatter,
            // Test equipment: a failed intent goes to stdout rather than to a crash reporter.
            diagnostics = StatusDiagnostics(
                log = { println(it) },
                onIntentError = { println("user command failed: $it") },
            ),
        ).also(onHostReady)
    }
    val state by host.container.stateFlow.collectAsState()
    // The joined-layer presets force a canned event, so this is non-null there → the QR renders.
    // The current membership settings for the reconfigure surface (capability `reconfigure-membership`).
    // The rename lifecycle for the heading's rename dialog (capability `event-rename`).

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
            state = state,
            cutoff = cutoffFormatter,
            // The one tap → intent table (spec `sync-status-screen`), exactly as the shipped app binds it.
            actions = statusActions(host),
        )
        }
    }
}
