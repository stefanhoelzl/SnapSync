package app.snapsync.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.snapsync.model.UiIntent
import app.snapsync.model.UiState
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.ui.StatusScreen
import app.snapsync.ui.components.LocalDarkThemeOverride
import app.snapsync.ui.statusActions
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone pane of an app that is running: the real `StatusScreen` rendering exactly what the app [shown] on its
 * screen, with every tap handed back as the intent it produces ([onIntent]) — the one tap → intent table the shipped
 * app binds. It builds no status host: the app's own is the one reducing. The forge, which has no app, keeps
 * [StatusPane].
 */
@Composable
fun ScreenPane(
    shown: StateFlow<UiState?>,
    cutoffFormatter: CutoffFormatter,
    onIntent: (UiIntent) -> Unit,
    // Test-only theme override for the phone pane, as [StatusPane] takes it.
    darkThemeOverride: Boolean? = null,
) {
    val state by shown.collectAsState()
    PhoneFrame {
        CompositionLocalProvider(LocalDarkThemeOverride provides darkThemeOverride) {
            state?.let { StatusScreen(state = it, cutoff = cutoffFormatter, actions = statusActions(onIntent)) }
                ?: Text("the app has shown nothing yet")
        }
    }
}
