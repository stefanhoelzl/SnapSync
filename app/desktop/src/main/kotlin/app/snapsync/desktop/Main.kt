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
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

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
                eventStatusSource = controller.eventStatusSource,
                // Harness share stub (test equipment): the joined-layer presets force CANNED_CONFIG, so
                // the host derives a real invite URL — copy it to the clipboard and log it rather than
                // open a native share sheet. Exercises the UI flow only; mutates no harness state.
                share = { url ->
                    runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
                    }
                    println("share invite → $url")
                },
                creationStatusSource = controller.creationStatusSource,
                creator = controller.creator,
            )
        }
        val state by host.container.stateFlow.collectAsState()
        // The joined-layer presets force a canned event, so this is non-null there → the QR renders.
        val inviteUrl by host.inviteUrl.collectAsState()

        MaterialTheme {
            Surface {
                Row(modifier = Modifier.padding(16.dp)) {
                    PhoneFrame {
                        // The container's `leave` defaults to a no-op (no leave fake wired), so the
                        // dialog is reviewable but Confirm is inert — the harness exercises UI only.
                        // Share is a clipboard/log stub; the QR renders from the canned invite URL.
                        StatusScreen(
                            state,
                            host::onRequestPermission,
                            host::onOpenSettings,
                            onLeaveEvent = host::onLeaveEvent,
                            onShareInvite = host::onShareInvite,
                            inviteUrl = inviteUrl,
                            onCreateEvent = host::onCreateEvent,
                        )
                    }
                    ControlPanel(controller)
                }
            }
        }
    }
}
