package app.snapsync.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.client.RigClient
import app.snapsync.dates.JvmDateFormatting
import app.snapsync.levers.DeviceAdvertisement
import app.snapsync.levers.RigLevers
import app.snapsync.levers.RigState
import app.snapsync.levers.ScreenLevers
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.dates.formatsFor
import app.snapsync.ui.StatusActions
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.components.DateFormatter
import app.snapsync.ui.statusActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The **mirror** (`docs/testing.md`, "The harness can mirror a remote host"): `:app:desktop:run
 * -Psnapsync.attach=<url>` attaches to a control-channel host — the JVM host, a simulator app, a phone — as a
 * typed-client client, instead of composing a world.
 *
 * The left pane is the real `StatusScreen`, re-composed here from the host's wire `UiState`, which it polls. Every tap
 * is handed to the host's screen as the intent it is (`ScreenLevers.tap`) — opening or dismissing a surface included,
 * which happens in the REMOTE container and comes back with the next state. The right pane shows the host's
 * advertisement and its latest state, and pulls no lever of its own.
 *
 * How the host is reached is not the harness's business: hosts bind loopback only, so an operator forwards a port
 * first (`ssh -L` to a Mac simulator, `usbmux forward` to a phone).
 */
@Composable
fun MirrorHarnessRoot(url: String) {
    val client = remember { RigClient(url) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RigState?>(null) }
    var advertisement by remember { mutableStateOf<DeviceAdvertisement?>(null) }
    var log by remember { mutableStateOf(listOf("mirroring $url")) }
    val note: (String) -> Unit = { line -> log = (log + line).takeLast(LOG_CAP) }

    LaunchedEffect(url) {
        // Polled on a real dispatcher, never the composition's frame clock: a clock the scene drives (the headless
        // driver's is virtual) would pause the mirror between frames and show a host that moved on long ago.
        withContext(Dispatchers.IO) {
            advertisement = runCatchingCancellable { client.levers(RigLevers).advertise() }
                .onFailure { note("the advertisement failed: $it") }.getOrNull()
            while (true) {
                state = runCatchingCancellable {
                    client.state()
                }.onFailure { note("the state read failed: $it") }.getOrNull() ?: state
                delay(POLL_MS)
            }
        }
    }

    // The screen reads the wall clock and zone of the machine it is drawn on, as both desktop harnesses do.
    // And its dates read as this machine's JVM writes them: the mirror re-composes the screen here, not on the device.
    val dates = remember { JvmDateFormatting() }
    val actions = remember { mirrorActions(client, scope, note) }

    MaterialTheme {
        Surface {
            Row(modifier = Modifier.padding(16.dp)) {
                PhoneFrame {
                    val shown = state
                    if (shown != null) {
                        StatusScreen(
                            state = shown.ui,
                            dateFormats = { tag -> DateFormatter(dates.formatsFor(tag)::format) },
                            actions = actions,
                        )
                    } else {
                        Text("waiting for $url …")
                    }
                }
                Column(
                    modifier = Modifier.padding(start = 16.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                ) {
                    Text("Host: ${advertisement?.host ?: "?"} — $url", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Honoured: ${advertisement?.honoured?.size ?: 0}, refused: ${advertisement?.refused?.size ?: 0}",
                    )
                    Text("Log", style = MaterialTheme.typography.titleSmall)
                    log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("State", style = MaterialTheme.typography.titleSmall)
                    Text(state?.toString() ?: "—", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * The screen's one tap table (`statusActions`), every tap handed to the host's screen as the intent it is: the host's
 * container reduces it and shows what it shows, which the next poll brings back here. Nothing is emulated locally — a
 * surface a tap opens is opened in the host's container, never here.
 */
private fun mirrorActions(client: RigClient, scope: CoroutineScope, note: (String) -> Unit): StatusActions {
    val screen = client.levers(ScreenLevers)
    return statusActions { intent ->
        scope.launch(Dispatchers.IO) {
            val answer = runCatchingCancellable { screen.tap(intent) }.fold({ "handed over" }, { it.toString() })
            note("tap $intent → $answer")
        }
    }
}

private const val POLL_MS = 300L
private const val LOG_CAP = 200
