package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.permission.PermissionStatus
import app.snapsync.presentation.UiState
import app.snapsync.ui.components.AppTheme
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.SetupCard
import app.snapsync.ui.components.StatusHero
import app.snapsync.ui.components.StatusIndicator

@Composable
fun StatusScreen(
    state: UiState,
    onRequestPermission: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    transientError: String? = null,
) {
    AppTheme {
        ScreenLayout(title = "SnapSync") {
            when (state) {
                UiState.Loading ->
                    StatusHero(StatusIndicator.Loading, "Loading …")
                is UiState.Setup ->
                    SetupGate(state, onRequestPermission, onOpenSettings, transientError)
                is UiState.InProgress ->
                    StatusHero(
                        StatusIndicator.InProgress,
                        "${state.synced} of ${state.total} images synced",
                        // Second caption: how many are uploading right now (omitted at 0 — e.g.
                        // photos discovered but not yet started), then the last-sync age (absent at a
                        // virgin "0 of N"). When neither applies there is no detail line.
                        inProgressCaption(state.inProgress, state.finishedAgo),
                    )
                UiState.NothingToSync ->
                    StatusHero(StatusIndicator.Complete, "Nothing to sync yet")
                is UiState.Completed ->
                    StatusHero(StatusIndicator.Complete, "${state.total} images synced", state.finishedAgo)
            }
        }
    }
}

// The InProgress detail line: the "{n} in progress" label only when something is actively uploading,
// joined to the last-sync age with " · ". Null (no detail line) when neither is present.
private fun inProgressCaption(inProgress: Int, finishedAgo: String?): String? {
    val active = if (inProgress > 0) "$inProgress in progress" else null
    return listOfNotNull(active, finishedAgo).joinToString(" · ").ifEmpty { null }
}

/**
 * The setup gate: a stack of two checkable cards. Storage is passive (completed by an external QR
 * scan, so no button) — its detail flips to [transientError] when a bad deeplink arrives. Photo
 * access carries the permission CTA. Each card collapses to a check once satisfied.
 */
@Composable
private fun SetupGate(
    state: UiState.Setup,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    transientError: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.storageConnected) {
            SetupCard(StatusIndicator.Success, "Storage connected")
        } else {
            SetupCard(
                indicator = if (transientError != null) StatusIndicator.Error else StatusIndicator.Waiting,
                title = "Connect your storage",
                detail = transientError ?: "Open the Camera app and scan your SnapSync QR code.",
            )
        }

        when (state.permission) {
            PermissionStatus.GRANTED ->
                SetupCard(StatusIndicator.Success, "Photo access granted")
            PermissionStatus.NOT_DETERMINED ->
                SetupCard(
                    indicator = StatusIndicator.Photos,
                    title = "Allow photo access",
                    detail = "SnapSync needs your photo library to back it up.",
                    action = { PrimaryButton("Allow access", onRequestPermission) },
                )
            PermissionStatus.DENIED ->
                SetupCard(
                    indicator = StatusIndicator.Error,
                    title = "Photo access denied",
                    detail = "Turn on photo access in Settings.",
                    action = { PrimaryButton("Open Settings", onOpenSettings) },
                )
        }
    }
}
