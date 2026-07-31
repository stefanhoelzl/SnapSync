package app.snapsync.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * The **full-stack world harness** (`:app:desktop:run`, capability `full-stack-harness`): the real
 * `StatusScreen` in a phone frame on the left — its counts **emerge** from the world's real
 * `LedgerBackedSyncStatusSource`, never forged — and a **world inspector** on the right that drives
 * `:test:world`'s control surface through a single [WorldInspectorController]. The operator plays the
 * OS: nothing auto-runs; **Invoke extension** runs one `process()`-shaped cycle by hand.
 *
 * This file compiles to `app.snapsync.desktop.FullStackHarnessKt` — deliberately distinct from the
 * forge harness's `app.snapsync.desktop.MainKt`, which shares this module since the migration
 * step-10 fold (`:app:desktop:run` = world, `:app:desktop:runForge` = forge). Thin wiring + Compose
 * only; all testable logic lives in `:test:world` and the presentation/status modules.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SnapSync — full-stack world",
        state = WindowState(size = DpSize(WORLD_WIDTH.dp, WORLD_HEIGHT.dp)),
    ) {
        WorldHarnessRoot()
    }
}

/** The world harness window's size — shared with the headless driver, which sizes its scene to match. */
const val WORLD_WIDTH: Int = 1240
const val WORLD_HEIGHT: Int = 950

/**
 * The harness's whole content, lifted out of the `Window` lambda so it can also be composed **without**
 * a window — `:test:harness-driver` renders exactly this into an offscreen scene, where the real stack
 * turns identically: the world's `scope.launch` work runs, and **Invoke extension** completes a real
 * upload cycle. Keeping it one composable is what makes the driver drive the *shipped* harness rather
 * than a copy of it.
 */
@Composable
fun WorldHarnessRoot() {
    val scope = rememberCoroutineScope()
    val controller = remember { WorldInspectorController(scope) }
    // Phone-pane theme override (test equipment): default Light, and held OUTSIDE the
    // `key(generation)` block below so a preset (fresh world) does not reset it.
    var dark by remember { mutableStateOf(false) }

    // Engine console tap: funnel the real stack's Kermit output into the inspector footer. Installed
    // once; a pure read of existing log output (no change to :test:world / production).
    remember { Logger.setLogWriters(ConsoleLogWriter(controller::appendConsole)); Unit }

    MaterialTheme {
        Surface {
            Row(modifier = Modifier.padding(16.dp)) {
                // The shared left pane (in :app:desktop): the real StatusScreen in a phone frame,
                // driven by the world's REAL sources. Keyed on the world generation so a preset
                // (fresh world) re-binds the StatusContainerHost to the new sources.
                key(controller.generation) {
                    StatusPane(
                        syncSource = controller.syncSource,
                        permissionSource = controller.permissionSource,
                        requester = controller.requester,
                        configSource = controller.configSource,
            creationStatusSource = controller.creationStatusSource,
                        creator = controller.creator,
                        downloadSource = controller.downloadSource,
                        // The REAL join gate over the world: create + scan reach the JoiningEvent
                        // surface (direction + cutoff rows), and confirm enrolls + provisions.
                        loadJoinDetails = controller::loadJoinDetails,
                        commitJoin = controller::commitJoin,
                        // Capture the constructed host so a minted event routes into ITS pending-join
                        // gate (onEventCreated); re-fires on each preset rebind (keyed on generation).
                        onHostReady = { controller.host = it },
                        // Harness share stub (test equipment): copy the invite URL to the clipboard
                        // and log it rather than open a native share sheet.
                        share = { url ->
                            runCatching {
                                Toolkit.getDefaultToolkit().systemClipboard
                                    .setContents(StringSelection(url), null)
                            }
                            controller.appendConsole("share invite → $url")
                        },
                        scope = scope,
                        // The real Leave affordance runs the faithful edge (imports retained).
                        leave = controller.leave,
                        // The real in-place reconfigure edge (capability `reconfigure-membership`).
                        reconfigure = controller.reconfigure,
                        // The real shareable-count query over the world gallery (capability `join-share-count`).
                        shareableCount = controller::loadShareableCount,
                        // The REAL bug-report command (capability `diagnostic-logging`): the world
                        // composes a configured reporter, so sending assembles a genuine dump over the
                        // world's ledger, download store, config and logs — and the world records it,
                        // inspectable without a device.
                        sendDiagnostics = controller.sendDiagnostics,
                        darkThemeOverride = dark,
                    )
                }
                WorldInspector(
                    controller,
                    dark = dark,
                    onDarkChange = { dark = it },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/** A Kermit [LogWriter] that forwards each formatted line to the engine console. */
private class ConsoleLogWriter(private val onLine: (String) -> Unit) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val line = buildString {
            append('[').append(severity.name.first()).append('/').append(tag).append("] ").append(message)
            throwable?.message?.let { append(" | ").append(it) }
        }
        onLine(line)
    }
}
