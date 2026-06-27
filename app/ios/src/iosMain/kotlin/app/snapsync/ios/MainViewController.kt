package app.snapsync.ios

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import app.snapsync.presentation.SetupEffect
import app.snapsync.ui.StatusScreen
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * The iOS entry point. The Swift app (iosApp/) calls [MainViewController] to obtain the root
 * UIViewController. The screen renders **live** state from the real stack assembled in
 * [SnapSyncRoot]: the Orbit container's `stateFlow`, with the gate intents routed back to the host.
 * It also collects the container's side effects to surface the transient invalid-link error on the
 * setup screen (self-clearing after a few seconds). `StatusScreen` wraps itself in `AppTheme`.
 */
@Suppress("FunctionName", "unused")
fun MainViewController() = ComposeUIViewController {
    val host = SnapSyncRoot.host
    val state by host.container.stateFlow.collectAsState()
    // The event's invite deeplink (null until an event is configured) — rendered as the join QR in the
    // joined layer and handed to the share sheet.
    val inviteUrl by host.inviteUrl.collectAsState()

    // Dev/test: apply a `SNAPSYNC_DEEPLINK` launch-env deeplink once per process (no-op in
    // production, where no such env var exists). Runs after `host` is realized; safe to repeat.
    LaunchedEffect(Unit) { SnapSyncRoot.applyLaunchEnvDeeplink() }

    var transientError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(host) {
        host.container.sideEffectFlow.collect { effect ->
            when (effect) {
                SetupEffect.InvalidConfigLink -> transientError = "That QR code wasn't valid."
            }
        }
    }
    // Self-clear the transient error a few seconds after it last appeared.
    LaunchedEffect(transientError) {
        if (transientError != null) {
            delay(4.seconds)
            transientError = null
        }
    }

    StatusScreen(
        state,
        host::onRequestPermission,
        host::onOpenSettings,
        onLeaveEvent = host::onLeaveEvent,
        onShareInvite = host::onShareInvite,
        inviteUrl = inviteUrl,
        transientError = transientError,
    )
}
