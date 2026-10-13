package app.snapsync.presentation

import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.model.AlbumKind
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.ui.state.AlbumStyle
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RefusalCause
import app.snapsync.ui.state.StoreName
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The domain's words as the screen states them, and the container's display half as a state of its own. */
class ScreenModelTest {

    @Test
    fun `each domain value has its display twin`() {
        assertEquals(listOf(AlbumStyle.COLLECTION, AlbumStyle.FOLDER), AlbumKind.entries.map { it.shown })
        assertEquals(listOf(StoreName.APP_STORE, StoreName.GOOGLE_PLAY), StoreKind.entries.map { it.shown })
        assertEquals(
            listOf(RefusalCause.DEVICE_MODIFIED, RefusalCause.DEVICE_UNVERIFIABLE, RefusalCause.APP_NOT_GENUINE),
            DeviceRefusal.entries.map { it.cause },
        )
    }

    @Test
    fun `the display half reads now and emits only what changed`() = runTest {
        val creating = ScreenModel(UiState(Layer.CreatingEvent), Shown())
        val source = MutableStateFlow(creating)
        val screen = Displayed { source }
        assertEquals(listOf(creating.ui), screen.replayCache)

        val seen = mutableListOf<UiState>()
        val collecting = launch { screen.take(2).toList(seen) }
        yield()
        // A change of the domain half alone shows nothing new.
        source.value = creating.copy(shown = Shown(store = StoreLink("https://apps.apple.com/app/id1", StoreKind.APP_STORE)))
        yield()
        source.value = ScreenModel(
            UiState(
                Layer.CreateEvent(
                    freshCreateForm(
                        CutoffFormatter(now = {
                            Instant.DISTANT_PAST
                        }, zone = TimeZone.UTC),
                    ),
                ),
            ),
            Shown(),
        )
        collecting.join()
        assertEquals(
            listOf(
                UiState(
                    Layer.CreatingEvent,
                ),
                UiState(
                    Layer.CreateEvent(
                        freshCreateForm(
                            CutoffFormatter(now = {
                                Instant.DISTANT_PAST
                            }, zone = TimeZone.UTC),
                        ),
                    ),
                ),
            ),
            seen,
        )
    }
}
