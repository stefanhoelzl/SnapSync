package app.snapsync.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SnapSync",
        state = WindowState(size = DpSize(FORGE_WIDTH.dp, FORGE_HEIGHT.dp)),
    ) {
        ForgeHarnessRoot()
    }
}

/** The forge harness window's size — shared with the headless driver, which sizes its scene to match. */
const val FORGE_WIDTH: Int = 800
const val FORGE_HEIGHT: Int = 950

/**
 * The harness's whole content, lifted out of the `Window` lambda so it can also be composed **without**
 * a window — `:test:harness-driver` renders exactly this into an offscreen scene. Keeping it one
 * composable is what makes the driver drive the *shipped* harness rather than a copy of it.
 */
@Composable
fun ForgeHarnessRoot() {
    val controller = remember { PanelController() }
    val scope = rememberCoroutineScope()
    // Phone-pane theme override (test equipment): default Light, matching the harness's appearance.
    var dark by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface {
            Row(modifier = Modifier.padding(16.dp)) {
                // The shared left pane (in :app:desktop): the real StatusScreen in a phone frame,
                // driven by the forge cells the PanelController exposes.
                StatusPane(
                    syncSource = controller.syncSource,
                    permissionSource = controller.permissionSource,
                    requester = controller.requester,
                    configSource = controller.configSource,
                    creationStatusSource = controller.creationStatusSource,
                    creator = controller.creator,
                    downloadSource = controller.downloadStatusSource,
                    // Harness share stub (test equipment): the joined-layer presets force
                    // CANNED_CONFIG, so the host derives a real invite URL — copy it to the
                    // clipboard and log it rather than open a native share sheet. Exercises the UI
                    // flow only; mutates no harness state.
                    share = { url ->
                        runCatching {
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
                        }
                        println("share invite → $url")
                    },
                    // Harness bug-report stub (test equipment): the forge composes no reporter, so a
                    // sent report echoes to the console and goes nowhere. It exists so the hidden
                    // double-tap, the sheet, and its disabled-until-written send are reviewable
                    // offscreen (capability `diagnostic-logging`). This is the HARNESS's own wiring —
                    // the shared forge host factory stays without a command, so the on-device forge
                    // composition (no DSN) still offers no affordance at all.
                    sendDiagnostics = { note, screen -> println("bug report [$screen] → $note") },
                    scope = scope,
                    darkThemeOverride = dark,
                    // The forge's join/switch and attestation cells, so the panel can forge the
                    // join gate (JoiningEvent / pendingSwitch) and SyncHealth.Unattested.
                    attested = controller.attestedState,
                    pending = controller.pendingJoinSource,
                )
                ControlPanel(controller, dark = dark, onDarkChange = { dark = it })
            }
        }
    }
}
