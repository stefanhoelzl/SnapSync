package app.snapsync.ios

import androidx.compose.ui.window.ComposeUIViewController
import app.snapsync.presentation.UiState
import app.snapsync.ui.StatusScreen

/**
 * The iOS entry point. The Swift app (iosApp/) calls [MainViewController] to obtain the root
 * UIViewController. For this first target the screen renders a single static [UiState] — there is
 * no live data source, matching the desktop app, which also has no live-ledger wiring yet.
 *
 * `StatusScreen` already wraps itself in `AppTheme`, so nothing else is assembled here.
 */
@Suppress("FunctionName", "unused")
fun MainViewController() = ComposeUIViewController {
    StatusScreen(UiState.InProgress(fraction = 0.6f, estimate = "about 2 min left"))
}
