package app.snapsync.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import app.snapsync.model.PlatformEntry
import app.snapsync.model.SceneMode
import app.snapsync.ui.StatusActions
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.components.LocalReduceMotion
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIColor
import platform.UIKit.UIViewController
import platform.UIKit.systemBackgroundColor

/**
 * The iOS entry point. The Swift app (iosApp/) calls [MainViewController] to obtain the root
 * UIViewController. The screen renders **live** state from the real stack assembled in
 * [SnapSyncRoot]: the Orbit container's `stateFlow`, with the gate intents routed back to the host.
 * The transient invalid-link error is the host's own self-clearing `transientError` StateFlow
 * (presentation-owned choreography — the set-then-clear decision left this untested shell at the
 * migration finale, step-12 D6). `StatusScreen` wraps itself in `AppTheme`.
 *
 * The host is [SnapSyncRoot.renderHost] — **always** the live stack (capability `ios-app-shell`).
 * Forged frames for a marketing screenshot are rendered by a separate binary that does not link this
 * module at all, so there is no forge path here to take.
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
// this reads its answer. It sits in its own function so the suppression names only this, rather than
// riding along with `MainViewController`'s unrelated `FunctionName`/`unused` opt-outs.
// Expiry: dies with the deferral, when CMP-5978 is fixed upstream and the mitigation can be deleted.
@Suppress("CyclomaticComplexMethod")
private fun sceneFor(mode: SceneMode): UIViewController = when (mode) {
    SceneMode.Deferred -> deferredScene()
    SceneMode.Live -> liveScene
}

/**
 * The placeholder installed while the app is not active: an empty view controller that composes nothing
 * and owns nothing.
 *
 * It paints the platform's own system background so it is not bare white. Three different things render
 * white otherwise — the launch screen (which configures no content), this placeholder, and a scene that
 * has been detached — and a reporter cannot tell them apart, nor can whoever reads their dump; on a
 * dark-appearance device it also flashes white on the way to a `#0C0E12` app. `systemBackgroundColor` is
 * ONE symbol and UIKit resolves light/dark itself, so this stays wiring: the shell branches on nothing
 * (`module-architecture`, "Shells are wiring only"). It is not the app's own background colour, which
 * would need a `userInterfaceStyle` read — a decision, and one this module may not hold.
 *
 * ⚠️ This does **not** make the three whites distinguishable in every appearance: in light mode the
 * system colour IS white. What separates them is the generation line in the log
 * ([SnapSyncRoot.onSceneActive]), not the pixels.
 *
 * (This KDoc used to say the backdrop "belongs to the window, which the scene delegate colours once".
 * Nothing colours any window — `SnapSyncSceneDelegate` implements only `willConnectTo` and `continue`,
 * and no `UIWindow`/`backgroundColor` exists anywhere in the app — so that claim was false, and it was
 * the reason this view was left untinted.)
 */
private fun deferredScene(): UIViewController {
    // Statements rather than `.apply { … }`: `detektAppShell` holds this module at straight-line
    // complexity and counts a trailing lambda against it. Suppressing would be the wrong trade for a
    // two-line body — `sceneFor`'s suppression is meant to stay the only one in this file.
    val placeholder = UIViewController(nibName = null, bundle = null)
    placeholder.view.backgroundColor = UIColor.systemBackgroundColor()
    return placeholder
}

/**
 * The live scene — **created on every call**, because that is what the platform's contract says this is.
 *
 * Apple, `UIViewControllerRepresentable.makeUIViewController(context:)`: *"Creates the view controller
 * object and configures its initial state… You must implement this method and use it to **create** your
 * view controller object. The system calls this method only once, when it creates your view controller for
 * the first time."* That "only once" is **per view identity**, and the Swift host's `.id(…)` exists
 * precisely to mint a new identity when the placeholder must become the live scene. Teardown then
 * *"removes your view controller cleanly"*.
 *
 * So handing back an instance already installed under the OUTGOING identity makes one object
 * simultaneously the thing the incoming host adopts and the thing the outgoing host removes — and the
 * removal wins. **Measured on device** (SE2, iOS 26.6, 2026-08-25): two builds differing only in this
 * line, both forced through a rebuild and both logging the identical two `MainViewController(mode=live)`
 * calls — memoized rendered a **white screen**, per-call rendered the app. That is the shape behind
 * Bugsink SNAPSYNC-15 and SNAPSYNC-24, and it is permanent, because the generation does not change again.
 *
 * This used to be `by lazy`, defended on the grounds that re-composing would discard screen-local Compose
 * state (an open reconfigure surface, a half-typed bug report, a scroll position) on every foreground.
 * That defence died with the generation rule: the only rebuild left is the placeholder → live swap, and
 * the placeholder composes nothing, so no screen-local state exists at that instant. The two changes are
 * not redundant — the generation rule removes the *spurious* rebuild, this removes the *consequence* of
 * any rebuild, including one nobody predicted.
 */
private val liveScene: UIViewController get() = composeScene()

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
                state = state,
                membership = membership,
                inviteUrl = inviteUrl,
                eventName = eventName,
                transientError = transientError,
                // The root's one system-bound formatter (migration step 9: the screen's default died with
                // the through-ports repayment; forge and live share this same instance).
                cutoff = SnapSyncRoot.cutoffFormatter,
                photoPermission = photoPermission,
                renameStatus = renameStatus,
                actions = StatusActions(
                    onRequestPermission = host::onRequestPermission,
                    onOpenSettings = host::onOpenSettings,
                    onLeaveEvent = host::onLeaveEvent,
                    onShareInvite = host::onShareInvite,
                    onSendDiagnostics = host.onSendDiagnostics,
                    onReconfigure = host::onReconfigure,
                    onCreateEvent = host::onCreateEvent,
                    onConfirmJoin = host::onConfirmJoin,
                    onAcknowledgeAccess = host::onAcknowledgeAccess,
                    onChoosePhotos = host::onChoosePhotos,
                    onCancelJoin = host::onCancelJoin,
                    onRetryLoad = host::onRetryLoad,
                    onRetryJoin = host::onRetryJoin,
                    onConfirmSwitch = host::onConfirmSwitch,
                    onCancelSwitch = host::onCancelSwitch,
                    // The join-time shareable-count preview (capability `join-share-count`): the
                    // permission-aware, no-network query, plus the live grant as its recompute trigger.
                    shareableCount = SnapSyncRoot.shareableCount,
                    // The heading rename (capability `event-rename`): the command, its lifecycle, and the
                    // latch reset the screen fires once it has acted on a terminal value.
                    onRenameEvent = host::onRenameEvent,
                    onRenameStatusConsumed = host::onRenameStatusConsumed,
                ),
            )
        }
    }
