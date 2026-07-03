package app.snapsync.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
 * `ListingSyncStatusSource`, never forged — and a **world inspector** on the right that drives
 * `:test:world`'s control surface through a single [WorldInspectorController]. The operator plays the
 * OS: nothing auto-runs; **Invoke extension** runs one `process()`-shaped cycle by hand.
 *
 * This file compiles to `app.snapsync.desktop.FullStackHarnessKt` — deliberately distinct from the
 * forge harness's `app.snapsync.desktop.MainKt`, which leaks transitively onto `:app:desktop:ui`'s
 * classpath (see `build.gradle.kts`). Thin wiring + Compose only; all testable logic lives in
 * `:test:world` and the presentation/status modules.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SnapSync — full-stack world",
        state = WindowState(size = DpSize(1240.dp, 950.dp)),
    ) {
        val scope = rememberCoroutineScope()
        val controller = remember { WorldInspectorController(scope) }

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
                            configStore = controller.configStore,
                            creationStatusSource = controller.creationStatusSource,
                            creator = controller.creator,
                            downloadSource = controller.downloadSource,
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
                        )
                    }
                    WorldInspector(controller, Modifier.weight(1f).fillMaxHeight())
                }
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
