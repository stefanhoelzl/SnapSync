package app.snapsync.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import app.snapsync.presentation.SetupEffect
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.components.LocalReduceMotion
import kotlin.time.Duration.Companion.seconds
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import kotlinx.coroutines.delay

/**
 * The iOS entry point. The Swift app (iosApp/) calls [MainViewController] to obtain the root
 * UIViewController. The screen renders **live** state from the real stack assembled in
 * [SnapSyncRoot]: the Orbit container's `stateFlow`, with the gate intents routed back to the host.
 * It also collects the container's side effects to surface the transient invalid-link error on the
 * setup screen (self-clearing after a few seconds). `StatusScreen` wraps itself in `AppTheme`.
 *
 * The host is [SnapSyncRoot.renderHost] — the live stack in production, or a forged-source host when
 * the dev/test `SNAPSYNC_FORGE_STATE` launch-env variable is set (capability `ios-app-shell`). Either
 * way the screen renders `container.stateFlow` live; the forge substitutes the container's inputs, not
 * a static `UiState`.
 */
@Suppress("FunctionName", "unused")
fun MainViewController() = ComposeUIViewController {
    val host = SnapSyncRoot.renderHost
    val state by host.container.stateFlow.collectAsState()
    // The event's invite link (null until an event is configured) — rendered as the join QR in the
    // joined layer and handed to the share sheet.
    val inviteUrl by host.inviteUrl.collectAsState()
    // The joined event's name for the screen title (fetched by id; null until fetched).
    val eventName by host.eventName.collectAsState()

    // Dev/test: apply a `SNAPSYNC_EVENT_LINK` launch-env event link once per process (no-op in
    // production, where no such env var exists). Runs after `host` is realized; safe to repeat.
    LaunchedEffect(Unit) { SnapSyncRoot.applyLaunchEnvEventLink() }
    // Dev/test: fill the library with `SNAPSYNC_SEED_PHOTOS` synthetic assets (no-op in production).
    LaunchedEffect(Unit) { SnapSyncRoot.applyLaunchEnvSeed() }

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

    // The platform's reduce-motion preference (capability `design-system`). Compose Multiplatform has no
    // cross-platform accessor for it, so the composition root supplies it — this is the only place that
    // knows. Read on each composition rather than `remember`ed: it is a cheap property read, and caching it
    // for the process would ignore a user who turns it on while the app is open.
    CompositionLocalProvider(LocalReduceMotion provides UIAccessibilityIsReduceMotionEnabled()) {
        StatusScreen(
            state,
            host::onRequestPermission,
            host::onOpenSettings,
            onLeaveEvent = host::onLeaveEvent,
            onShareInvite = host::onShareInvite,
            inviteUrl = inviteUrl,
            eventName = eventName,
            onCreateEvent = host::onCreateEvent,
            transientError = transientError,
            onConfirmJoin = host::onConfirmJoin,
            onAcknowledgeAccess = host::onAcknowledgeAccess,
            onCancelJoin = host::onCancelJoin,
            onRetryLoad = host::onRetryLoad,
            onRetryJoin = host::onRetryJoin,
            onConfirmSwitch = host::onConfirmSwitch,
            onCancelSwitch = host::onCancelSwitch,
        )
    }
}
