package app.snapsync.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import app.snapsync.model.PlatformEntry
import app.snapsync.model.SceneMode
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.components.LocalReduceMotion
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIViewController

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
 *
 * **A scene is composed only while the app is active** (capability `ios-app-shell`). iOS connects UI
 * scenes in `UISceneActivationState.background`, so a process woken by a silent push or a `BGTask` would
 * otherwise stand up a Compose runtime and Metal renderer it cannot draw with, hold it across the window
 * in which iOS reclaims GPU resources, and then present it — the shape behind two production reports of a
 * blank/corrupted status screen. [SnapSyncRoot.sceneMode] answers with the pure, tested resolver and this
 * function switches on it once; the deferred branch returns a bare placeholder and composes nothing. The
 * resolved mode is logged, and that line is how the deferral is verified on device.
 *
 * This is a **mitigation for an upstream renderer defect** (CMP-5978), not an architectural preference —
 * see the resolver for the expiry trigger.
 */
@PlatformEntry
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    val mode = SnapSyncRoot.sceneMode()
    return SnapSyncRoot.platformEntry("MainViewController", params = "mode=${mode.diagnosticName}") {
        sceneFor(mode)
    }
}

// The ONE switch on the resolved scene mode, and the only decision in this file. It is a decision by
// construction — the sealed type exists so the compiler fails closed if a third mode is ever added — but
// the DECIDING is not here: `resolveScene` is pure and `commonTest`-covered on JVM and the simulator, and
// this reads its answer. The same shape, and the same reason, as `SnapSyncRoot`'s one `when (mode)` on
// `CompositionMode`. It sits in its own function so the suppression names only this, rather than riding
// along with `MainViewController`'s unrelated `FunctionName`/`unused` opt-outs.
// Expiry: dies with the deferral, when CMP-5978 is fixed upstream and the mitigation can be deleted.
@Suppress("CyclomaticComplexMethod")
private fun sceneFor(mode: SceneMode): UIViewController = when (mode) {
    SceneMode.Deferred -> deferredScene()
    SceneMode.Live -> liveScene
}

/**
 * The placeholder installed while the app is not active: an empty view controller that composes nothing
 * and owns nothing. Its view is deliberately left untinted — the backdrop belongs to the window, which the
 * scene delegate colours once, so this stays a bare placeholder rather than a second opinion about
 * appearance.
 */
private fun deferredScene(): UIViewController = UIViewController(nibName = null, bundle = null)

/**
 * The live scene, realized **once per process** and reused thereafter — `by lazy` rather than a
 * hand-rolled null check, so the shell carries no branch for it.
 *
 * The reuse is what keeps the Swift side a transcriber. The scene delegate installs
 * `MainViewController()` unconditionally at both connect and activation, and every activation after the
 * first must be a no-op — re-composing on each foreground would discard screen-local Compose state (an
 * open reconfigure surface, a half-typed bug report, a scroll position) on every ordinary app switch,
 * which is the option this change deliberately did not take. Memoizing here means Swift needs no
 * "is it already installed?" branch, and the decision stays in tested Kotlin.
 */
private val liveScene: UIViewController by lazy { composeScene() }

private fun composeScene(): UIViewController =
    ComposeUIViewController {
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
