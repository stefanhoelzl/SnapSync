package app.snapsync.presentation

import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.EventStart
import app.snapsync.model.GalleryAccess
import app.snapsync.model.JoinLoad
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.RangeChoice
import app.snapsync.model.SyncStatus
import app.snapsync.model.UiIntent
import app.snapsync.model.UiState
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.orbitmvi.orbit.test.testWithInternalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The range calendars' intents (capabilities `create-event`, `join-event`): each gesture reaches the open calendar, or
 * else the create form's; the calendar over the join gate opens on the resolved range, takes its span on OK, and closes
 * unchanged on Cancel; and Create creates only a form that may be created. The clock reads Thursday 9 July 2026, 12:00.
 */
class RangeIntentsTest {

    @Test
    fun `every gesture reaches the create form's calendar`() = runTest {
        host(backgroundScope).testWithInternalState(this) {
            runOnCreate()
            val on = containerHost
            on.onIntent(UiIntent.RangeDay(day(10)))
            testScheduler.runCurrent()
            assertEquals(day(10), picker().endDay)
            on.onIntent(UiIntent.RangeDragEnd(day(12)))
            testScheduler.runCurrent()
            assertEquals(day(12), picker().endDay)
            on.onIntent(UiIntent.RangeDragStart(day(8)))
            testScheduler.runCurrent()
            assertEquals(day(8), picker().from.date)
            on.onIntent(UiIntent.RangeSweep(day(14), day(16)))
            testScheduler.runCurrent()
            assertEquals(day(14) to day(16), picker().from.date to picker().endDay)
            on.onIntent(UiIntent.RangeFromHour(9))
            testScheduler.runCurrent()
            on.onIntent(UiIntent.RangeFromMinute(30))
            testScheduler.runCurrent()
            assertEquals(LocalDateTime(2026, 7, 14, 9, 30), picker().from)
            on.onIntent(UiIntent.RangeUntilMinuteDrag)
            testScheduler.runCurrent()
            assertEquals(12, picker().untilHour, "a blank end hour fills with the clock's")
            on.onIntent(UiIntent.RangeUntilHour(18))
            testScheduler.runCurrent()
            on.onIntent(UiIntent.RangeUntilMinute(15))
            testScheduler.runCurrent()
            assertEquals(LocalDateTime(2026, 7, 16, 18, 15), picker().until)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `Create creates only a form that may be created and creates what it shows`() = runTest {
        val created = mutableListOf<Triple<String, EventStart, app.snapsync.model.EventEnd>>()
        host(backgroundScope, created = { n, s, e -> created += Triple(n, s, e) }).testWithInternalState(this) {
            runOnCreate()
            containerHost.onIntent(UiIntent.ConfirmCreate)
            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.CreateName("Party"))
            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.ConfirmCreate)
            testScheduler.runCurrent()
            assertTrue(created.isEmpty(), "no end time yet: $created")
            containerHost.onIntent(UiIntent.RangeUntilHour(13))
            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.RangeUntilMinute(0))
            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.ConfirmCreate)
            testScheduler.runCurrent()
            assertEquals(
                listOf(Triple("Party", eventStart("2026-07-09T12:00:00Z"), eventEnd("2026-07-09T13:00:00Z"))),
                created,
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `the join gate's calendar opens on the resolved range then takes its span or closes unchanged`() = runTest {
        host(backgroundScope).testWithInternalState(this) {
            runOnCreate()
            // Nothing to open, and nothing open to take or close: inert.
            val before = containerHost.screen.value
            containerHost.onIntent(UiIntent.RangePickerOpen)
            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.RangePickerConfirm)
            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.RangePickerDismiss)
            testScheduler.runCurrent()
            assertEquals(before, containerHost.screen.value)

            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            awaitShown { (it.joining?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready }

            containerHost.onIntent(UiIntent.RangePickerOpen)
            testScheduler.runCurrent()

            testScheduler.runCurrent()
            val opened = assertNotNull(shownRange().picker)
            assertEquals(
                LocalDateTime(2026, 7, 4, 18, 0) to LocalDateTime(2026, 7, 20, 18, 0),
                opened.from to opened.until,
            )
            containerHost.onIntent(UiIntent.RangePickerDismiss)
            testScheduler.runCurrent()
            assertNull(shownRange().picker, "Cancel closes the calendar")
            assertEquals(RangeChoice.WHOLE_EVENT, shownRange().preset, "and changes nothing")

            containerHost.onIntent(UiIntent.RangePickerOpen)
            testScheduler.runCurrent()

            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.RangeDay(day(13)))
            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.RangePickerConfirm)
            testScheduler.runCurrent()
            assertNotNull(shownRange().picker, "a range without its last day is not taken")
            containerHost.onIntent(UiIntent.RangeDay(day(15)))
            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.RangePickerConfirm)
            testScheduler.runCurrent()
            awaitShown { it.joining?.range?.preset == RangeChoice.CUSTOM }
            assertNull(shownRange().picker)
            assertEquals(
                LocalDateTime(2026, 7, 13, 18, 0) to LocalDateTime(2026, 7, 15, 18, 0),
                shownRange().from to shownRange().until,
            )

            // A preset closes the calendar it sits on.
            containerHost.onIntent(UiIntent.RangePickerOpen)
            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.RangePreset(RangeChoice.WHOLE_EVENT))
            testScheduler.runCurrent()
            awaitShown { it.joining?.range?.preset == RangeChoice.WHOLE_EVENT }
            assertNull(shownRange().picker)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a stated custom range applies as the calendar's would`() = runTest {
        host(backgroundScope).testWithInternalState(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            awaitShown { (it.joining?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready }
            containerHost.onIntent(UiIntent.RangeCustom(LocalDateTime(2026, 7, 6, 9, 0), null))
            testScheduler.runCurrent()
            awaitShown { it.joining?.range?.preset == RangeChoice.CUSTOM }
            assertEquals(LocalDateTime(2026, 7, 6, 9, 0), shownRange().from)
            cancelAndIgnoreRemainingItems()
        }
    }

    private val UiState.joining get() = layer as? Layer.JoiningEvent

    private fun org.orbitmvi.orbit.test.OrbitScopedTestContextInternal<ScreenModel, *, *, StatusContainerHost>.picker() =
        assertNotNull((containerHost.screen.value.layer as Layer.CreateEvent).form.picker)

    private fun org.orbitmvi.orbit.test.OrbitScopedTestContextInternal<ScreenModel, *, *, StatusContainerHost>.shownRange() =
        assertNotNull((containerHost.screen.value.layer as Layer.JoiningEvent).range)

    private suspend fun org.orbitmvi.orbit.test.OrbitScopedTestContextInternal<ScreenModel, *, *, *>.awaitShown(
        predicate: (UiState) -> Boolean,
    ) {
        do {
            val shown = awaitShown()
        } while (!predicate(shown))
    }

    private fun day(day: Int) = LocalDate(2026, 7, day)

    private fun host(
        scope: CoroutineScope,
        created: (String, EventStart, app.snapsync.model.EventEnd) -> Unit = { _, _, _ -> },
    ) = StatusContainerHost(
        StatusSources(
            object : SyncStatusSource {
                override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Loading)
            },
            MutableStateFlow(GalleryAccess.GRANTED),
            MutableStateFlow(null),
            creation = MutableStateFlow(CreationStatus.Idle),
        ),
        scope,
        commands = testCommands(create = created),
        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
        queries = joinDetails { _ ->
            JoinLoad.Found(
                "Picnic",
                eventStart("2026-07-04T18:00:00Z"),
                eventEnd("2026-07-20T18:00:00Z"),
                deletesAt("2026-08-03T18:00:00Z"),
            )
        },
        diagnostics = testDiagnostics(),
    )

    private companion object {
        const val EVENT_ID = "11111111-1111-4111-8111-111111111111"
    }
}
