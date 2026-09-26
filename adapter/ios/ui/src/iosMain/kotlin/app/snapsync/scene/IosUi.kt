package app.snapsync.scene

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import app.snapsync.logging.invocation
import app.snapsync.model.PlatformEntry
import app.snapsync.model.UiState
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.ui.statusActions
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIViewController
import platform.UIKit.systemBackgroundColor

/**
 * The iOS [Ui]: the Compose scene SwiftUI hosts, and the rule that decides when one exists (capability `sync-status`).
 *
 * **SwiftUI pulls the scene** at `makeUIViewController` ([viewController]); this answers at ask-time, on the main
 * thread, from the shared [SceneRecord]: a process woken into the background is handed a blank placeholder and builds
 * no screen, while a process that is — or has ever been — active is handed a live one. A live pull first calls the
 * core's `onLive` handler, which assembles the status host and [show]s its current state, so the first frame already
 * renders it.
 *
 * [show] keeps the latest state in a conflated flow that every live view controller collects. A **new** controller is
 * built per live pull, as SwiftUI asks: memoizing one handed SwiftUI a controller it had already torn down, which
 * rendered a white screen. While no screen is live, [show] only updates the flow.
 *
 * [onSceneActive] is SwiftUI's synchronous `didBecomeActive` call: it records the activation and answers the
 * [SceneRecord.generation] SwiftUI binds to `.id(…)` — a value the shell binds, not a command it obeys, so the "when
 * does the scene exist" rule stays in tested Kotlin rather than in a Swift conditional.
 *
 * [cutoff] is the process's one formatter — the same instance the status host reduces with — so the screen and the
 * host render one capture date one way.
 */
class IosUi(
    private val record: SceneRecord,
    private val cutoff: CutoffFormatter,
    private val log: Logger,
) : Ui {
    private val shown = MutableStateFlow<UiState?>(null)
    private var handlers: UiHandlers? = null

    override fun listen(handlers: UiHandlers) {
        this.handlers = handlers
    }

    override fun show(state: UiState) {
        shown.value = state
    }

    /** The scene SwiftUI asked for: a placeholder while the process has never been active, else a live screen. */
    @PlatformEntry
    fun viewController(): UIViewController {
        val mode = record.resolve(UIApplication.sharedApplication.applicationState.value)
        return log.invocation("MainViewController", params = "mode=${mode.diagnosticName}") {
            val registered = handlers
            when {
                mode == SceneMode.Deferred -> placeholder()
                // The composition registers at launch, before SwiftUI's first pull; a pull before it is a wiring
                // fault, answered with the placeholder rather than a throw across the ObjC boundary.
                registered == null -> placeholder().also { log.e { "a live scene was pulled before the Ui port was listened to" } }
                else -> liveScreen(registered)
            }
        }
    }

    /**
     * The app became active, as SwiftUI observes it — record it, and answer the scene generation SwiftUI binds to
     * `.id(…)`. Logged with the value it answered (capability `privacy-security`): what separates a healthy process
     * from one carrying a stale rebuild signal is that value.
     */
    @PlatformEntry
    fun onSceneActive(): Int = log.invocation("onSceneActive", result = { "generation=$it" }) {
        record.markActive()
        record.generation
    }

    private fun placeholder(): UIViewController {
        val placeholder = UIViewController(nibName = null, bundle = null)
        placeholder.view.backgroundColor = UIColor.systemBackgroundColor()
        return placeholder
    }

    private fun liveScreen(registered: UiHandlers): UIViewController {
        registered.onLive()
        val actions = statusActions(registered.onIntent)
        return ComposeUIViewController {
            val state by shown.collectAsState()
            CompositionLocalProvider(LocalReduceMotion provides UIAccessibilityIsReduceMotionEnabled()) {
                state?.let { StatusScreen(state = it, cutoff = cutoff, actions = actions) }
            }
        }
    }
}
