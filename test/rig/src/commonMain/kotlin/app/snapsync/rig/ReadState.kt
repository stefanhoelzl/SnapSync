package app.snapsync.rig

import app.snapsync.compose.AppCore
import app.snapsync.levers.DownloadView
import app.snapsync.levers.Readiness
import app.snapsync.levers.RigState
import app.snapsync.model.TimestampText
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiState

/**
 * Read the whole snapshot: every field a flow's current value, or a fact of the build.
 *
 * Reading it is the screen's pull, as the phone's foreground poll pulls: the ledger-count read-model is refreshed first
 * (`ReadingLedgerCountsSource.refresh()`, the one consistent read the status source performs), so the reduced [UiState]
 * the next read returns reflects what the uploads did. The counts themselves are not reported.
 */
internal suspend fun readState(core: AppCore, host: StatusContainerHost, hooks: RigHooks): RigState {
    core.ledgerCounts.refresh()
    val progress = core.downloadStatusSource.progress.value
    // The invite URL and the inline create error live INSIDE the UI state, so the rig
    // reports exactly what the screen is rendering. The membership's own facts — its direction and capture range —
    // are not on the screen as such; they are read from the membership the screen is reduced from.
    val ui = host.screen.value
    // Resolved once the SCREEN shows the joined event, not merely once the membership is read: a caller polling on this
    // then acts on what the member sees.
    val config = core.membership.value?.takeIf { (ui.layer as? Layer.Joined)?.membership?.eventId == it.eventId.value }
    return RigState(
        ui = ui,
        ready = Readiness(
            configResolved = config != null,
            eventId = config?.eventId?.value,
            direction = config?.direction?.name,
            minPhotoDate = config?.minPhotoDate?.at?.let(TimestampText::format),
            maxPhotoDate = config?.maxPhotoDate?.at?.let(TimestampText::format),
        ),
        download = DownloadView(
            downloaded = progress.downloaded,
            total = progress.total,
            inFlight = progress.inFlight,
        ),
        build = hooks.buildFacts(),
        // The OS's answer is grant-dependent, so the grant it was read under travels with it (see [OsExtensionView]).
        osExtension = hooks.readOsExtension(core.photoPermission.value.name),
    )
}
