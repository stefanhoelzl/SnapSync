package app.snapsync.ios.forge

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import app.snapsync.model.PermissionStatus
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.forgeStatusHost
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.components.LocalReduceMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIViewController

/**
 * The forge binary's entry point — the whole of it.
 *
 * This renders the **real** `StatusScreen` over **forged sources**: the container's inputs are substituted,
 * never its output, so every frame a capture shows was produced by the real presentation reduction. A state
 * the reduction cannot reach cannot be captured, which is the property the App Store listing depends on
 * (capability `ios-appstore-metadata`, "The committed captures depict the real screen in a reachable
 * state").
 *
 * ## What is NOT here, and why that is the point
 *
 * No `SnapSyncRoot`. No `AppCore`. No composition mode, no shell delegate, no OS entry points. This binary
 * does not link `:app:ios`, so a forge process has no route to the live stack — not a guarded one, not an
 * inert one, none. That replaces `ForgeShell`, which implemented about fifteen `Shell` members solely to
 * make each entry point do nothing, every one of which had to keep doing nothing correctly forever.
 *
 * It also means a capture cannot contact a backend, perform attestation, or read a photo library, because
 * there is no code in this binary that could.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController {
    val host = forgeHost
    val state by host.container.stateFlow.collectAsState()
    val inviteUrl by host.inviteUrl.collectAsState()
    val eventName by host.eventName.collectAsState()
    val membership by host.membership.collectAsState()
    val renameStatus by host.renameStatus.collectAsState()
    val transientError by host.transientError.collectAsState()

    CompositionLocalProvider(LocalReduceMotion provides UIAccessibilityIsReduceMotionEnabled()) {
        StatusScreen(
            state,
            host::onRequestPermission,
            host::onOpenSettings,
            onLeaveEvent = host::onLeaveEvent,
            onShareInvite = host::onShareInvite,
            onSendDiagnostics = host.onSendDiagnostics,
            membership = membership,
            onReconfigure = host::onReconfigure,
            inviteUrl = inviteUrl,
            eventName = eventName,
            onCreateEvent = host::onCreateEvent,
            transientError = transientError,
            onConfirmJoin = host::onConfirmJoin,
            onAcknowledgeAccess = host::onAcknowledgeAccess,
            onChoosePhotos = host::onChoosePhotos,
            onCancelJoin = host::onCancelJoin,
            onRetryLoad = host::onRetryLoad,
            onRetryJoin = host::onRetryJoin,
            onConfirmSwitch = host::onConfirmSwitch,
            onCancelSwitch = host::onCancelSwitch,
            cutoff = cutoffFormatter,
            shareableCount = { _, _ -> null },
            // A constant, exactly as `ForgeShell` supplied: this is the shareable-count row's recompute
            // trigger, and there is no live grant in this binary to observe. The forged frame's own
            // permission is one of the preset's inputs and reaches the screen through the reduction.
            photoPermission = PermissionStatus.GRANTED,
            onRenameEvent = host::onRenameEvent,
            renameStatus = renameStatus,
            onRenameStatusConsumed = host::onRenameStatusConsumed,
        )
    }
}

/**
 * The forged host, realized once.
 *
 * `!!` because the resolution below already refused an unrecognized state, loudly, at process start — so by
 * the time anything renders, the name is known good. A silent fallback to some default state would be the
 * worst possible failure here: the capture would succeed, look plausible, and depict the wrong screen, and
 * nothing downstream checks (the six committed raws have no automated check — a person looks at them).
 */
private val forgeHost by lazy { forgeStatusHost(forgeState, scope, cutoffFormatter)!! }

/**
 * Which state to render.
 *
 * `SNAPSYNC_FORGE_STATE` survives the launch-trigger retirement, but it moved: this file does not exist in a
 * production build, so the variable is observable by no shipped code and is inert **by construction** rather
 * than by a runtime check. That is the same footing `SNAPSYNC_RIG_PORT` already stands on, and it is why
 * production Kotlin can still declare zero `SNAPSYNC_*` literals with this here.
 *
 * An absent or unrecognized value **fails loudly**. The old behaviour was to fall through to the live stack,
 * which made sense when forge was a mode of the shipped app; in a binary whose only purpose is to render a
 * forged frame, there is nothing to fall through to, and a screenshot run that silently captured the wrong
 * thing is exactly the failure nothing downstream would catch.
 */
private val forgeState: String by lazy {
    val raw = NSProcessInfo.processInfo.environment["SNAPSYNC_FORGE_STATE"] as? String
    requireNotNull(raw?.takeIf { app.snapsync.presentation.isForgeState(it) }) {
        "SNAPSYNC_FORGE_STATE must name a recognized forge state; got '${raw ?: "nothing"}'. This binary " +
            "renders forged frames and nothing else — there is no live stack to fall back to."
    }
}

/** Forged sources need a scope for the container; nothing in this binary outlives the process. */
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * The formatter the screen requires. Fixed to UTC rather than the device's zone: a marketing capture must
 * render the same cutoff text wherever the runner happens to be, or the committed raws would differ between
 * CI regions for no reason a reader could see.
 */
private val cutoffFormatter = CutoffFormatter(now = { Clock.System.now() }, zone = TimeZone.UTC)
