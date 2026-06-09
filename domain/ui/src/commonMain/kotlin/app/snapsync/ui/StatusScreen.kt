package app.snapsync.ui

import androidx.compose.runtime.Composable
import app.snapsync.presentation.UiState
import app.snapsync.ui.components.AppTheme
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.StatusText
import app.snapsync.ui.components.UploadProgress

@Composable
fun StatusScreen(state: UiState) {
    AppTheme {
        ScreenLayout(title = "SnapSync") {
            when (state) {
                UiState.Idle -> StatusText("Up to date")
                is UiState.Uploading -> {
                    StatusText("Uploading…")
                    UploadProgress(done = state.done, total = state.total)
                }
            }
        }
    }
}
