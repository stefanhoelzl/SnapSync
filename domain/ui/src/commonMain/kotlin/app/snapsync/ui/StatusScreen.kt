package app.snapsync.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.presentation.UiState
import app.snapsync.ui.components.AppTheme
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.StatusHero
import app.snapsync.ui.components.StatusIndicator

@Composable
fun StatusScreen(
    state: UiState,
    onRequestPermission: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    AppTheme {
        ScreenLayout(title = "SnapSync") {
            when (state) {
                UiState.PermissionAsk -> {
                    StatusHero(
                        StatusIndicator.Photos,
                        "Sync your photos",
                        "SnapSync needs access to your photo library",
                    )
                    Spacer(Modifier.height(24.dp))
                    PrimaryButton("Allow access", onRequestPermission)
                }
                UiState.PermissionDenied -> {
                    StatusHero(
                        StatusIndicator.Error,
                        "Photo access denied",
                        "Turn on photo access in system settings",
                    )
                    Spacer(Modifier.height(24.dp))
                    PrimaryButton("Open Settings", onOpenSettings)
                }
                UiState.NeverSynced ->
                    StatusHero(StatusIndicator.Warning, "No sync yet")
                is UiState.InProgress ->
                    StatusHero(StatusIndicator.Progress(state.fraction), "Sync in progress", state.estimate)
                UiState.Suspended ->
                    StatusHero(StatusIndicator.Waiting, "Waiting to sync")
                is UiState.Complete ->
                    StatusHero(StatusIndicator.Success, "Sync complete", state.finishedAgo)
                is UiState.Incomplete ->
                    StatusHero(StatusIndicator.Warning, "Sync incomplete", state.finishedAgo)
            }
        }
    }
}
