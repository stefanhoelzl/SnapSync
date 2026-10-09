package app.snapsync.android.scene

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import app.snapsync.model.BeforeListen
import app.snapsync.model.EntryScope
import app.snapsync.model.HandlerSlot
import app.snapsync.model.PlatformEntry
import app.snapsync.model.UiState
import app.snapsync.model.invocation
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.ScreenDates
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.ui.statusActions
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Android [Ui]: the Compose screen an activity sets as its content.
 *
 * **The activity pulls the screen** at its creation ([content]), so a process a worker or a push started in the
 * background builds none — the property iOS gets from its deferred scene, here for free: no activity, no screen. A pull
 * first calls the core's `onLive` handler, which assembles the status host and [show]s its current state, so the first
 * frame already renders it. A re-created activity (a rotation, a return from the back stack) pulls again; `onLive` is
 * idempotent.
 *
 * [show] keeps the latest state in a conflated flow every live screen collects; while no screen is live it only updates
 * the flow.
 *
 * [cutoff] is the process's one formatter — the same instance the status host reduces with — so the screen and the host
 * render one capture date one way. [dates] is the process's date formatting, the screen's every date read through it.
 */
class AndroidUi(
    private val cutoff: CutoffFormatter,
    private val dates: ScreenDates,
    private val log: Logger,
) : Ui {
    private val shown = MutableStateFlow<UiState?>(null)
    private val handlers = HandlerSlot<UiHandlers>("Ui", BeforeListen.Thrown)

    override fun listen(handlers: UiHandlers) = this.handlers.set(handlers)

    override fun show(state: UiState) {
        shown.value = state
    }

    /** The screen an activity is being created with: the live status screen over the composition's handlers. */
    @PlatformEntry
    fun content(): @Composable () -> Unit = log.invocation(EntryScope.None, "onCreateActivity") {
        val registered = handlers.require("an activity's creation")
        registered.onLive()
        val actions = statusActions(registered.onIntent)
        val screen: @Composable () -> Unit = {
            val state by shown.collectAsState()
            CompositionLocalProvider(LocalReduceMotion provides animationsOff()) {
                state?.let {
                    StatusScreen(state = it, cutoff = cutoff, dateFormats = dates::formats, actions = actions)
                }
            }
        }
        screen
    }

    /** Whether the person switched animations off — the platform's "remove animations" sets the animator scale to 0. */
    @Composable
    private fun animationsOff(): Boolean =
        Settings.Global.getFloat(LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
