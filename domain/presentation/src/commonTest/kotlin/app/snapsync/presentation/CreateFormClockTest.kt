package app.snapsync.presentation

import app.snapsync.feature.creation.readmodel.CreationFailureReason
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.ForegroundReturn
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SyncStatus
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.orbitmvi.orbit.test.testWithInternalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The create draft against the clock and the app's foreground life (capability `create-event`): its start follows the clock
 * until the host chooses the range, and only a long absence starts it afresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateFormClockTest {

    @Test
    fun `each return to the foreground keeps the draft and only a long absence starts a fresh one`() = runTest {
        // Capability `create-event`, "A long absence starts a fresh draft".
        val foreground = MutableStateFlow(ForegroundReturn.NONE)
        val failed = CreationStatus.Failed(CreationFailureReason.SERVER)
        host(backgroundScope, { NOON }, failed, foreground).testWithInternalState(this) {
            runOnCreate()
            containerHost.create.onName("Party")
            val named = awaitShown().layer as Layer.CreateEvent
            assertEquals("Party" to 0, named.form.name to named.form.draft)
            // Returns within the limit keep what the host entered.
            foreground.value = ForegroundReturn(1, awayFor = null)
            foreground.value = ForegroundReturn(2, awayFor = 14.minutes + 59.seconds)
            expectNoItems()
            // A fresh draft keeps the failure message: it stays until the next attempt.
            foreground.value = ForegroundReturn(3, awayFor = 15.minutes)
            val fresh = awaitShown().layer as Layer.CreateEvent
            assertEquals(
                Triple("", 1, ScreenMessage.CREATE_FAILED),
                Triple(fresh.form.name, fresh.form.draft, fresh.error),
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `an untouched create range follows the clock minute by minute and a chosen one stays`() = runTest {
        var now = NOON
        host(backgroundScope, { now }).testWithInternalState(this) {
            fun shownStart() = (containerHost.screen.value.layer as Layer.CreateEvent).form.picker.from
            runOnCreate()
            now = Instant.parse("2026-07-09T12:10:00Z")
            advanceTimeBy(60_001)
            assertEquals(LocalDateTime(2026, 7, 9, 12, 10), shownStart())

            containerHost.onIntent(UiIntent.RangeDay(LocalDate(2026, 7, 10)))
            now = Instant.parse("2026-07-09T12:20:00Z")
            advanceTimeBy(120_001)
            assertEquals(LocalDateTime(2026, 7, 9, 12, 10), shownStart(), "a choice in the range freezes the start")
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun host(
        scope: CoroutineScope,
        now: () -> Instant,
        creation: CreationStatus = CreationStatus.Idle,
        foreground: MutableStateFlow<ForegroundReturn> = MutableStateFlow(ForegroundReturn.NONE),
    ) = StatusContainerHost(
        StatusSources(
            object : SyncStatusSource {
                override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Loading)
            },
            MutableStateFlow(GalleryAccess.GRANTED),
            MutableStateFlow(null),
            creation = MutableStateFlow(creation),
            foreground = foreground,
        ),
        scope,
        commands = testCommands(),
        cutoffFormatter = CutoffFormatter(now = now, zone = TimeZone.UTC),
        queries = noQueries,
        diagnostics = testDiagnostics(),
        reduction = Dispatchers.Default,
    )

    private companion object {
        val NOON: Instant = Instant.parse("2026-07-09T12:00:00Z")
    }
}
