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
import app.snapsync.control.RigClient
import app.snapsync.model.FromChoice
import app.snapsync.model.UntilChoice
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.Layer
import app.snapsync.presentation.RangeForm
import app.snapsync.rig.DeviceAdvertisement
import app.snapsync.rig.RigState
import app.snapsync.ui.AccessActions
import app.snapsync.ui.JoinGateActions
import app.snapsync.ui.JoinedActions
import app.snapsync.ui.ParticipationActions
import app.snapsync.ui.StatusActions
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.SurfaceActions
import app.snapsync.ui.SwitchActions
import app.snapsync.ui.components.RangeChoiceActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Clock

/**
 * The **mirror** (capability `full-stack-harness`, "The harness can mirror a remote host"): `:app:desktop:run
 * -Psnapsync.attach=<url>` attaches to a control-channel host — the JVM host, a simulator app, a phone — as a
 * typed-client client, instead of composing a world.
 *
 * The left pane is the real `StatusScreen`, re-composed here from the host's wire `UiState`, which it polls. A tap
 * becomes the host's `/user` intent. A tap with no intent — opening or dismissing a surface, which lives in the REMOTE
 * container — is inert and logged: a surface opened here would be a surface the host never opened. The right pane
 * shows the host's advertisement and its latest state, and pulls no lever of its own.
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
            advertisement = runCatching { client.device() }.onFailure { note("GET /device failed: $it") }.getOrNull()
            while (true) {
                state = runCatching { client.state() }.onFailure { note("GET /device/state failed: $it") }.getOrNull() ?: state
                delay(POLL_MS)
            }
        }
    }

    // The screen reads the wall clock and zone of the machine it is drawn on, as both desktop harnesses do.
    val cutoff = remember { CutoffFormatter(now = { Clock.System.now() }, zone = TimeZone.currentSystemDefault()) }
    val actions = remember { mirrorActions(client, scope, { state }, note) }

    MaterialTheme {
        Surface {
            Row(modifier = Modifier.padding(16.dp)) {
                PhoneFrame {
                    state?.let { StatusScreen(state = it.ui, cutoff = cutoff, actions = actions) }
                        ?: Text("waiting for $url …")
                }
                Column(modifier = Modifier.padding(start = 16.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Text("Host: ${advertisement?.host ?: "?"} — $url", style = MaterialTheme.typography.titleMedium)
                    Text("Honoured: ${advertisement?.honoured?.size ?: 0}, refused: ${advertisement?.refused?.size ?: 0}")
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
 * The screen's tap table, bound to the host's `/user` intents. Every tap the host has an intent for posts it; every
 * other tap is inert and says so in the log — never emulated locally.
 */
private fun mirrorActions(
    client: RigClient,
    scope: CoroutineScope,
    latest: () -> RigState?,
    note: (String) -> Unit,
): StatusActions {
    fun post(name: String, vararg params: Pair<String, String>): () -> Unit = {
        scope.launch(Dispatchers.IO) { note("/user/$name → ${runCatching { client.user(name, mapOf(*params)) }.getOrElse { it }}") }
    }
    fun inert(what: String): () -> Unit = { note("$what: no /user intent — the surface lives in the host's container") }
    val form: () -> RangeForm? = { (latest()?.ui?.layer as? Layer.JoiningEvent)?.form }
    fun instant(local: LocalDateTime) = local.toInstant(TimeZone.currentSystemDefault()).toString()
    fun direction(share: Boolean, receive: Boolean): String? = when {
        share && receive -> "both"
        share -> "upload"
        receive -> "download"
        else -> null
    }
    fun setDirection(share: Boolean, receive: Boolean) {
        direction(share, receive)?.let { post("setRange", "direction" to it)() } ?: note("neither direction: nothing to join as")
    }
    return StatusActions(
        join = JoinGateActions(
            onConfirmJoin = post("confirmJoin"),
            onRetryJoin = post("retryJoin"),
            onAcknowledgeAccess = inert("acknowledge access"),
            onCancelJoin = post("cancelJoin"),
            onRetryLoad = post("retryLoad"),
        ),
        joined = JoinedActions(
            onLeaveEvent = post("leave"),
            onShareInvite = inert("share invite"),
            onReconfigure = inert("save settings"),
            onRenameEvent = { event, name -> post("rename", "event" to event, "name" to name)() },
            onRenameStatusConsumed = post("renameStatusConsumed"),
        ),
        access = AccessActions(
            onRequestPermission = inert("request permission"),
            onOpenSettings = inert("open settings"),
            onChoosePhotos = inert("choose photos"),
        ),
        switch = SwitchActions(onConfirmSwitch = post("confirmSwitch"), onCancelSwitch = inert("cancel switch")),
        surfaces = SurfaceActions(
            onConfirmLeaveOpen = inert("open leave confirmation"),
            onConfirmLeaveDismiss = inert("dismiss leave confirmation"),
            onRenameOpen = inert("open rename"),
            onRenameDismiss = inert("dismiss rename"),
            onOpenReconfigure = inert("open settings surface"),
            onCancelReconfigure = inert("cancel settings surface"),
            onReportBugOpen = inert("open bug report"),
            onReportBugDismiss = inert("dismiss bug report"),
        ),
        onCreateEvent = { name, startsAt, endsAt ->
            post("create", "name" to name, "startsAt" to startsAt.toString(), "endsAt" to endsAt.toString())()
        },
        onOpenLink = { url -> note("open link $url: the mirror opens no browser") },
        participation = ParticipationActions(
            choices = RangeChoiceActions(
                onFromPreset = { preset ->
                    when (preset) {
                        FromChoice.EVENT_START -> post("setRange", "from" to "eventStart")()
                        FromChoice.NOW -> post("setRange", "from" to "now")()
                        FromChoice.CUSTOM -> note("a custom start is set by picking a date")
                    }
                },
                onFromCustom = { local -> post("setRange", "cutoff" to instant(local))() },
                onUntilPreset = { preset ->
                    when (preset) {
                        UntilChoice.EVENT_END -> post("setRange", "until" to "eventEnd")()
                        UntilChoice.CUSTOM -> note("a custom end is set by picking a date")
                    }
                },
                onUntilCustom = { local -> post("setRange", "until" to instant(local))() },
            ),
            onShareOn = { on -> form()?.let { setDirection(on, it.receiveOn) } ?: note("no join form open") },
            onReceiveOn = { on -> form()?.let { setDirection(it.shareOn, on) } ?: note("no join form open") },
            onSaveToAlbum = { on -> post("setRange", "saveToAlbum" to on.toString())() },
        ),
        onSendDiagnostics = { text, screen -> post("sendDiagnostics", "note" to text, "screen" to screen)() },
    )
}

private const val POLL_MS = 300L
private const val LOG_CAP = 200
