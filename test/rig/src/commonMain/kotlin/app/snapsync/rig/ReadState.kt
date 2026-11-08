package app.snapsync.rig

import app.snapsync.compose.AppCore
import app.snapsync.format.TimestampText
import app.snapsync.levers.DownloadView
import app.snapsync.levers.Readiness
import app.snapsync.levers.RigState
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiState

/**
 * Read the whole snapshot: every field a flow's current value, or a fact of the build.
 *
 * It reads nothing of its own: the screen it reports follows the stores while the app is in front, as on the phone, so
 * the counts in [UiState] move then and stand still while the app is away. A caller that needs what landed with the
 * app away reads the backend. The counts themselves are not reported.
 */
internal suspend fun readState(core: AppCore, host: StatusContainerHost, hooks: RigHooks): RigState {
    val progress = core.downloadProgress.value
    // The invite URL and the inline create error live INSIDE the UI state, so the rig
    // reports exactly what the screen is rendering. The membership's own facts — its direction and capture range —
    // are not on the screen as such; they are read from the membership the screen is reduced from.
    val ui = host.screen.value
    // Resolved once the SCREEN shows the joined event, not merely once the membership is read: a caller polling on this
    // then acts on what the member sees.
    val joined = core.membership.value.held?.takeIf {
        (ui.layer as? Layer.Joined)?.membership?.eventId == it.event.id.value
    }
    return RigState(
        ui = ui,
        ready = Readiness(
            configResolved = joined != null,
            eventId = joined?.event?.id?.value,
            direction = joined?.share?.direction?.name,
            minPhotoDate = joined?.share?.range?.from?.at?.let(TimestampText::format),
            maxPhotoDate = joined?.share?.range?.until?.at?.let(TimestampText::format),
        ),
        download = DownloadView(
            downloaded = progress.downloaded,
            total = progress.total,
            inFlight = progress.inFlight,
        ),
        build = hooks.buildFacts(),
        // The OS's answer is grant-dependent, so the grant it was read under travels with it (see [OsExtensionView]).
        osExtension = hooks.readOsExtension(RigGrant.name(core.photoGrant.state.value)),
    )
}
