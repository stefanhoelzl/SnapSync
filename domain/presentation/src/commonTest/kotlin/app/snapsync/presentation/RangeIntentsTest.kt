package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.GalleryAccess
import app.snapsync.model.JoinLoad
import app.snapsync.model.SyncStatus
import app.snapsync.model.TimestampText
import app.snapsync.model.encodeEventUrl
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        val created = mutableListOf<Triple<String, EventStart, app.snapsync.domain.event.EventEnd>>()
        host(backgroundScope, created = { n, s, e, _ -> created += Triple(n, s, e) }).testWithInternalState(this) {
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
                listOf(
                    Triple(
                        "Party",
                        EventStart(TimestampText.parse("2026-07-09T12:00:00Z")),
                        EventEnd(TimestampText.parse("2026-07-09T13:00:00Z")),
                    ),
                ),
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

            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(testEventId(EVENT_ID))))
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
            assertEquals(RangeChoice.EVENT_START, shownRange().preset, "and changes nothing")

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
            containerHost.onIntent(UiIntent.RangePreset(RangeChoice.EVENT_START))
            testScheduler.runCurrent()
            awaitShown { it.joining?.range?.preset == RangeChoice.EVENT_START }
            assertNull(shownRange().picker)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a range already over asks how long guests have to join before anything is created`() = runTest {
        val created = mutableListOf<Created>()
        host(backgroundScope, created = { n, s, e, o -> created += Created(n, s, e, o) }).testWithInternalState(this) {
            runOnCreate()
            pastRange { testScheduler.runCurrent() }
            containerHost.onIntent(UiIntent.ConfirmCreate)
            testScheduler.runCurrent()
            assertTrue(created.isEmpty(), "nothing is created yet: $created")
            val sheet = assertNotNull(form().keepOpen, "the sheet asks")
            assertEquals(
                listOf(1 to day(10), 7 to day(16), 14 to day(23), 30 to LocalDate(2026, 8, 8)),
                sheet.choices.map { it.days to it.until },
                "each choice beside the day it leads to",
            )

            // Cancelling keeps the draft.
            val draft = form().copy(keepOpen = null)
            containerHost.onIntent(UiIntent.KeepOpenDismiss)
            testScheduler.runCurrent()
            assertEquals(draft, form(), "the form is as the host left it")
            assertTrue(created.isEmpty(), "and nothing was created")

            // A choice creates at once, guests joining for that many days from now.
            containerHost.onIntent(UiIntent.ConfirmCreate)
            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.KeepOpenFor(7))
            testScheduler.runCurrent()
            assertEquals(
                listOf(
                    Created(
                        "Party",
                        EventStart(TimestampText.parse("2026-07-01T12:00:00Z")),
                        EventEnd(TimestampText.parse("2026-07-02T13:00:00Z")),
                        OpenUntil(TimestampText.parse("2026-07-16T12:00:00Z")),
                    ),
                ),
                created,
            )
            assertNull(form().keepOpen, "the sheet closed")

            // A retry — after a create that failed — asks again; a tap that outlived the sheet creates nothing.
            containerHost.onIntent(UiIntent.KeepOpenFor(7))
            testScheduler.runCurrent()
            assertEquals(1, created.size, "no sheet, no create")
            containerHost.onIntent(UiIntent.ConfirmCreate)
            testScheduler.runCurrent()
            assertNotNull(form().keepOpen, "Create asks again")
            containerHost.onIntent(UiIntent.KeepOpenFor(2))
            testScheduler.runCurrent()
            assertEquals(1, created.size, "a number the sheet does not offer creates nothing")
            // The form beneath the sheet emptied of its name: the answer closes the sheet and creates nothing.
            containerHost.onIntent(UiIntent.CreateName(""))
            containerHost.onIntent(UiIntent.KeepOpenFor(7))
            testScheduler.runCurrent()
            assertEquals(1, created.size, "a form that may not be created is not")
            assertNull(form().keepOpen)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `an event ending a minute from now is created without the sheet`() = runTest {
        val created = mutableListOf<Created>()
        host(backgroundScope, created = { n, s, e, o -> created += Created(n, s, e, o) }).testWithInternalState(this) {
            runOnCreate()
            containerHost.onIntent(UiIntent.CreateName("Party"))
            containerHost.onIntent(UiIntent.RangeDay(day(9)))
            containerHost.onIntent(UiIntent.RangeUntilHour(12))
            containerHost.onIntent(UiIntent.RangeUntilMinute(1))
            testScheduler.runCurrent()
            containerHost.onIntent(UiIntent.ConfirmCreate)
            testScheduler.runCurrent()
            assertNull(form().keepOpen)
            assertEquals(
                listOf(EventEnd(TimestampText.parse("2026-07-09T12:01:00Z")) to null),
                created.map { it.ends to it.openUntil },
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    /** One create the container fired. */
    private data class Created(
        val name: String,
        val starts: EventStart,
        val ends: app.snapsync.domain.event.EventEnd,
        val openUntil: OpenUntil?,
    )

    /** "Party", 1 July 12:00 to 2 July 13:00 — over a week before the clock's 9 July. */
    private fun org.orbitmvi.orbit.test.OrbitScopedTestContextInternal<ScreenModel, *, *, StatusContainerHost>.pastRange(
        settle: () -> Unit,
    ) {
        containerHost.onIntent(UiIntent.CreateName("Party"))
        containerHost.onIntent(UiIntent.RangeDay(day(1)))
        containerHost.onIntent(UiIntent.RangeDay(day(2)))
        containerHost.onIntent(UiIntent.RangeUntilHour(13))
        containerHost.onIntent(UiIntent.RangeUntilMinute(0))
        settle()
        assertTrue(form().canCreate, "the past range is complete: ${form().picker}")
    }

    private fun org.orbitmvi.orbit.test.OrbitScopedTestContextInternal<ScreenModel, *, *, StatusContainerHost>.form() =
        (containerHost.screen.value.layer as Layer.CreateEvent).form

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
        created: (String, EventStart, EventEnd, OpenUntil?) -> Unit =
            { _, _, _, _ -> },
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
                EventStart(TimestampText.parse("2026-07-04T18:00:00Z")),
                EventEnd(TimestampText.parse("2026-07-20T18:00:00Z")),
                DeletesAt(TimestampText.parse("2026-08-03T18:00:00Z")),
            )
        },
        diagnostics = testDiagnostics(),
        reduction = Dispatchers.Default,
    )

    private companion object {
        const val EVENT_ID = "11111111-1111-4111-8111-111111111111"
    }
}
