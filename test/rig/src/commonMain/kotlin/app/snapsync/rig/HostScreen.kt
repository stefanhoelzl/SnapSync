package app.snapsync.rig

import app.snapsync.levers.ScreenLevers
import app.snapsync.mock.MockedSystem
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The screen as this host serves it (`ScreenLevers`): a tap reaches the app as the platform's UI hands it over, on [lane]
 * — the thread a tap arrives on — and is answered once it is handed over, never once its work is done: the work it
 * starts is observed as the screen observes it.
 *
 * Where the world's screen is the mock's, every method is the mock's — the live screen included. Where it is the
 * platform's, a tap goes through [ui] (the platform's UI, decorated for the channel), what it shows is [shown], and the
 * live screen is the platform's to build: refused.
 */
fun MockWorld.screenService(lane: CoroutineContext, ui: RigUi?, shown: () -> UiState?): HostService<ScreenLevers> {
    val mock = device.screen.takeIf { isMocked(MockedSystem.SCREEN) }
    val implementation = object : ScreenLevers {
        override suspend fun tap(intent: UiIntent) = withContext(lane) {
            if (mock != null) mock.tap(intent) else checkNotNull(ui) { "no screen to tap" }.dispatch(intent)
        }

        override suspend fun shown(): UiState? = mock?.shown?.value ?: shown()

        override suspend fun live() = withContext(lane) { checkNotNull(mock).live() }
    }
    val refused = if (mock == null) {
        ScreenLevers.refusing(
            "live",
            why = "the screen is the platform's on this launch, and the platform builds its own live screen",
        )
    } else {
        emptyMap()
    }
    return HostService(ScreenLevers, implementation, refused)
}
