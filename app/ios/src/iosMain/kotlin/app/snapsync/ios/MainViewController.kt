package app.snapsync.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.components.LocalReduceMotion
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/**
 * The iOS entry point. The Swift app (iosApp/) calls [MainViewController] to obtain the root
 * UIViewController. The screen renders **live** state from the real stack assembled in
 * [SnapSyncRoot]: the Orbit container's `stateFlow`, with the gate intents routed back to the host.
 * The transient invalid-link error is the host's own self-clearing `transientError` StateFlow
 * (presentation-owned choreography — the set-then-clear decision left this untested shell at the
 * migration finale, step-12 D6). `StatusScreen` wraps itself in `AppTheme`.
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
    // The current membership settings for the reconfigure surface (capability `reconfigure-membership`).
    val membership by host.membership.collectAsState()
    // The rename lifecycle for the heading's rename dialog (capability `event-rename`).
    val renameStatus by host.renameStatus.collectAsState()

    // Dev/test: apply the membership-mutating launch-env triggers (leave → create → event-link) once
    // per process (no-op in production, where no such env vars exist). Runs after `host` is realized;
    // safe to repeat (guarded by a `by lazy`).
    LaunchedEffect(Unit) { SnapSyncRoot.applyLaunchEnvMembership() }
    // Dev/test: fill the library with `SNAPSYNC_SEED_PHOTOS` synthetic assets (no-op in production).
    LaunchedEffect(Unit) { SnapSyncRoot.applyLaunchEnvSeed() }

    // The transient invalid-link error — presentation-owned, self-clearing (see the host).
    val transientError by host.transientError.collectAsState()

    // The photo grant — the shareable-count row's recompute trigger (a late first-join resolve makes the
    // count appear); capability `join-share-count`.
    val photoPermission by SnapSyncRoot.photoPermission.collectAsState()

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
            // The root's one system-bound formatter (migration step 9: the screen's default died with
            // the through-ports repayment; forge and live share this same instance).
            cutoff = SnapSyncRoot.cutoffFormatter,
            // The join-time shareable-count preview (capability `join-share-count`): the permission-aware,
            // no-network query, plus the live grant as its recompute trigger.
            shareableCount = SnapSyncRoot.shareableCount,
            photoPermission = photoPermission,
            // The heading rename (capability `event-rename`): the command, its lifecycle, and the latch
            // reset the screen fires once it has acted on a terminal value.
            onRenameEvent = host::onRenameEvent,
            renameStatus = renameStatus,
            onRenameStatusConsumed = host::onRenameStatusConsumed,
        )
    }
}
