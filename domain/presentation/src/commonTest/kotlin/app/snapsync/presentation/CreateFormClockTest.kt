package app.snapsync.presentation

import app.snapsync.control.Verifies
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.foreground.ForegroundReturn
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.presentation.Switches.mobileData
import app.snapsync.presentation.UiIntents.onIntent
import app.snapsync.query.RequestStatus
import app.snapsync.query.membership.MembershipShown
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The create draft against the clock and the app's foreground life (capability `create-event`): its start follows the
 * clock
 * until the host chooses the range, and only a long absence starts it afresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateFormClockTest {

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The host chooses the event's date range and its end time",
        scenario = "The start is preset to now",
    )
    @Verifies(
        spec = "create-event",
        requirement = "The host chooses the event's date range and its end time",
        scenario = "The last day starts as today, but not the end time",
    )
    fun `the create form opens with its start at now to the minute, its last day today and no end time`() = runTest {
        host(backgroundScope, { Instant.parse("2026-07-09T18:04:37Z") }).testWithInternalState(this) {
            runOnCreate()
            containerHost.onIntent(UiIntent.CreateName("Party"))
            val picker = (awaitShown().layer as Layer.CreateEvent).form.picker
            assertEquals(LocalDateTime(2026, 7, 9, 18, 4), picker.from)
            assertEquals(LocalDate(2026, 7, 9), picker.endDay)
            assertEquals(null to null, picker.untilHour to picker.untilMinute)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "A long absence starts a fresh draft",
        scenario = "Coming back after 15 minutes starts over",
    )
    @Verifies(
        spec = "create-event",
        requirement = "A long absence starts a fresh draft",
        scenario = "Coming back sooner keeps the draft",
    )
    fun `each return to the foreground keeps the draft and only a long absence starts a fresh one`() = runTest {
        // Capability `create-event`, "A long absence starts a fresh draft".
        val foreground = MutableStateFlow(ForegroundReturn.NONE)
        val failed = Creating.answered(CreateResult.Unverified)
        host(backgroundScope, { NOON }, failed, foreground).testWithInternalState(this) {
            runOnCreate()
            containerHost.onIntent(UiIntent.CreateName("Party"))
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
    @Verifies(
        spec = "create-event",
        requirement = "The start follows the clock until the host chooses the range",
        scenario = "A choice in the range freezes the start",
    )
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
        creation: RequestStatus<CreateResult> = Creating.Idle,
        foreground: MutableStateFlow<ForegroundReturn> = MutableStateFlow(ForegroundReturn.NONE),
    ) = TestCommands().let { commands ->
        testHost(
            testQueries(
                MutableStateFlow(SharingProgressRead.NotYetRead),
                MutableStateFlow(PhotoGrant.FULL),
                MutableStateFlow(MembershipShown.None),
                creation = MutableStateFlow(creation),
                foreground = foreground,
                requests = commands.requests.flows,
                network = AlwaysOnline,
                mobileData = AlwaysAllowed,
            ),
            scope,
            commands = commands.door,
            cutoffFormatter = CutoffFormatter(now = instants(now), zone = TimeZone.UTC),
            shareableCount = noQueries,
            joinDetails = unanswered,
            reduction = Dispatchers.Default,
        )
    }

    private companion object {

        val NOON: Instant = Instant.parse("2026-07-09T12:00:00Z")
    }
}
