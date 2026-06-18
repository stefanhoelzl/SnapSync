package app.snapsync.ios

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import app.snapsync.ui.StatusScreen

/**
 * The iOS entry point. The Swift app (iosApp/) calls [MainViewController] to obtain the root
 * UIViewController. The screen now renders **live** state from the real stack assembled in
 * [SnapSyncRoot]: the Orbit container's `stateFlow`, with the gate intents routed back to the host
 * (which calls the PhotoKit permission adapter). `StatusScreen` wraps itself in `AppTheme`.
 */
@Suppress("FunctionName", "unused")
fun MainViewController() = ComposeUIViewController {
    val host = SnapSyncRoot.host
    val state by host.container.stateFlow.collectAsState()
    StatusScreen(state, host::onRequestPermission, host::onOpenSettings)
}
