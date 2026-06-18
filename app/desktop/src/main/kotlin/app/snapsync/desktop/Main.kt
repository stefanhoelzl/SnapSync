package app.snapsync.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.ui.StatusScreen

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SnapSync",
        state = WindowState(size = DpSize(800.dp, 950.dp)),
    ) {
        val controller = remember { PanelController() }
        val scope = rememberCoroutineScope()
        val host = remember {
            StatusContainerHost(
                controller.syncSource,
                controller.permissionSource,
                controller.requester,
                controller.configSource,
                controller.configStore,
                scope,
            )
        }
        val state by host.container.stateFlow.collectAsState()

        MaterialTheme {
            Surface {
                Row(modifier = Modifier.padding(16.dp)) {
                    PhoneFrame {
                        StatusScreen(state, host::onRequestPermission, host::onOpenSettings)
                    }
                    ControlPanel(controller)
                }
            }
        }
    }
}
