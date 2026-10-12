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
import app.snapsync.dates.JvmDateFormatting
import app.snapsync.model.runCatchingCancellable
import app.snapsync.presentation.url
import app.snapsync.rig.DeviceAdvertisement
import app.snapsync.rig.RigState
import app.snapsync.services.dates.formatsFor
import app.snapsync.ui.AccessActions
import app.snapsync.ui.CreateActions
import app.snapsync.ui.JoinGateActions
import app.snapsync.ui.JoinedActions
import app.snapsync.ui.MenuActions
import app.snapsync.ui.ParticipationActions
import app.snapsync.ui.StatusActions
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.SurfaceActions
import app.snapsync.ui.SwitchActions
import app.snapsync.ui.WithdrawalActions
import app.snapsync.ui.components.DateFormatter
import app.snapsync.ui.components.RangeChoiceActions
import app.snapsync.ui.components.RangeGestures
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.RangeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * The **mirror** (`docs/testing.md`, "The harness can mirror a remote host"): `:app:desktop:run
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
            advertisement = runCatchingCancellable { client.device() }.onFailure { note("GET /device failed: $it") }.getOrNull()
            while (true) {
                state = runCatchingCancellable {
                    client.state()
                }.onFailure { note("GET /device/state failed: $it") }.getOrNull() ?: state
                delay(POLL_MS)
            }
        }
    }

    // The screen reads the wall clock and zone of the machine it is drawn on, as both desktop harnesses do.
    // And its dates read as this machine's JVM writes them: the mirror re-composes the screen here, not on the device.
    val dates = remember { JvmDateFormatting() }
    val actions = remember { mirrorActions(client, scope, { state }, note) }

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
        scope.launch(Dispatchers.IO) {
            note("/user/$name → ${runCatchingCancellable { client.user(name, mapOf(*params)) }.getOrElse { it }}")
        }
    }
    fun inert(what: String): () -> Unit = { note("$what: no /user intent — the surface lives in the host's container") }
    val inertGestures = RangeGestures(
        onDay = { note("a range calendar's day: no /user intent") },
        onDragStart = { note("a range drag: no /user intent") },
        onDragEnd = { note("a range drag: no /user intent") },
        onSweep = { _, _ -> note("a range sweep: no /user intent") },
        onFromHour = { note("a time wheel: no /user intent") },
        onFromMinute = { note("a time wheel: no /user intent") },
        onUntilHour = { note("a time wheel: no /user intent") },
        onUntilMinute = { note("a time wheel: no /user intent") },
        onUntilMinuteDrag = inert("a time wheel"),
    )
    val form: () -> RangeView? = { (latest()?.ui?.layer as? Layer.JoiningEvent)?.range }
    fun instant(local: LocalDateTime) = local.toInstant(TimeZone.currentSystemDefault()).toString()
    fun direction(share: Boolean, receive: Boolean): String? = when {
        share && receive -> "both"
        share -> "upload"
        receive -> "download"
        else -> null
    }
    fun setDirection(share: Boolean, receive: Boolean) {
        direction(share, receive)?.let {
            post("setRange", "direction" to it)()
        } ?: note("neither direction: nothing to join as")
    }
    return StatusActions(
        join = JoinGateActions(
            onConfirmJoin = post("confirmJoin"),
            onRetryJoin = post("retryJoin"),
            onCancelJoin = post("cancelJoin"),
            onRetryLoad = post("retryLoad"),
        ),
        joined = JoinedActions(
            onLeaveEvent = post("leave"),
            onShareInvite = inert("share invite"),
            onQrOpen = inert("show QR code"),
            onQrDismiss = inert("dismiss QR code"),
            withdrawal = WithdrawalActions(
                onStopSharing = inert("stop sharing"),
                onKeepSharing = inert("keep sharing"),
            ),
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
            onReportRefusal = { inert("open bug report about a refusal")() },
        ),
        // The form is the host's: Create posts it as the host shows it. The mirror does not edit it — its typing and its
        // calendar have no `/user` verb — so a form is filled on the host, or created whole with `/user/create`.
        create = CreateActions(
            onName = { note("type the name on the host: the mirror's field has no /user verb") },
            range = inertGestures,
            onCreate = {
                val form = (latest()?.ui?.layer as? Layer.CreateEvent)?.form
                val until = form?.picker?.until
                if (form != null && until != null) {
                    post(
                        "create",
                        "name" to form.name,
                        "startsAt" to form.picker.from.toString(),
                        "endsAt" to until.toString(),
                    )()
                } else {
                    note("the host's form is not complete")
                }
            },
        ),
        onOpenStore = { note("open the store: the mirror opens no browser") },
        participation = ParticipationActions(
            choices = RangeChoiceActions(
                onPreset = { preset ->
                    when (preset) {
                        RangeChoice.WHOLE_EVENT -> post("setRange", "range" to "wholeEvent")()
                        RangeChoice.FROM_NOW -> post("setRange", "range" to "fromNow")()
                        RangeChoice.CUSTOM -> note("a custom range is set by picking dates")
                    }
                },
                onOpen = inert("open the range calendar"),
                gestures = inertGestures,
                onConfirm = inert("take the calendar's range"),
                onDismiss = inert("close the range calendar"),
            ),
            onShareOn = { on -> form()?.let { setDirection(on, it.receiveOn) } ?: note("no join form open") },
            onReceiveOn = { on -> form()?.let { setDirection(it.shareOn, on) } ?: note("no join form open") },
            onSaveToAlbum = { on -> post("setRange", "saveToAlbum" to on.toString())() },
        ),
        menu = MenuActions(
            onSendDiagnostics = { text, screen -> post("sendDiagnostics", "note" to text, "screen" to screen)() },
            onMenuOpen = inert("open menu"),
            onMenuDismiss = inert("dismiss menu"),
            onReportBug = inert("open bug report from the menu"),
            onOpenLink = { link -> note("open ${link.url}: the mirror opens no browser") },
            onReportNoticeDismiss = inert("dismiss report notice"),
            onMobileData = { on -> post("mobileData", "on" to on.toString())() },
        ),
    )
}

private const val POLL_MS = 300L
private const val LOG_CAP = 200
