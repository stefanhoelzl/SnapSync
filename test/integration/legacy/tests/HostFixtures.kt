package app.snapsync.integration

import app.snapsync.model.UserCommands
import app.snapsync.presentation.StatusDiagnostics

/** Diagnostics that go nowhere: the integration tests assert UiState and world outcomes, not log lines. */
internal fun quietDiagnostics() = StatusDiagnostics(log = {}, onIntentError = {})

/** [this] composed bundle with [leave] and [commitJoin] replaced — every other command stays the real one. */
internal fun UserCommands.replacing(
    leave: suspend () -> Unit = this.leave,
    commitJoin: suspend (
        String, String, app.snapsync.model.EventStart, app.snapsync.model.EventEnd, app.snapsync.model.DeletesAt,
        app.snapsync.model.CaptureCutoff, app.snapsync.model.CaptureCeiling, app.snapsync.model.Direction, Boolean,
    ) -> app.snapsync.model.JoinCommit = this.commitJoin,
) = UserCommands(
    leave, create, commitJoin, share, requestAccess, openSettings, openLink, choosePhotos, reconfigure, rename,
    resetRename, sendDiagnostics,
)
