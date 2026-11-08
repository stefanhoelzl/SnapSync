package app.snapsync.presentation

import app.snapsync.control.Verifies
import app.snapsync.presentation.range.EventRange
import app.snapsync.ui.state.CoarseDuration
import app.snapsync.ui.state.CreateStep
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The create form's rules (capability `create-event`): when Create is offered, and what the line above it says. */
class CreateDraftTest {

    private val clock = CutoffFormatter(now = instants({ Instant.parse("2026-07-06T12:00:00Z") }), zone = TimeZone.UTC)
    private val display = Display(clock, noWindow("the create form resolves no event window"))
    private val from = LocalDateTime(2026, 7, 6, 12, 0)

    private fun draft(name: String, range: EventRange) = CreateDraft(
        CreateDraftSession(),
        name,
        range,
        rangeTouched = true,
    )

    private fun draft(name: String, endDay: LocalDate, until: LocalTime) =
        draft(name, EventRange(from, endDay, until, endPending = false))

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The host chooses the event's date range and its end time",
        scenario = "A name alone does not allow Create",
    )
    @Verifies(
        spec = "create-event",
        requirement = "The host chooses the event's date range and its end time",
        scenario = "A same-day event needs only its end time",
    )
    fun `Create is offered only for a named draft whose end follows its start within the event window`() {
        assertEquals(
            LocalDateTime(2026, 7, 6, 13, 0),
            display.creatableEnd(draft("Party", from.date, LocalTime(13, 0))),
        )
        assertNull(display.creatableEnd(draft(" ", from.date, LocalTime(13, 0))), "no name")
        assertNull(display.creatableEnd(draft("Party", EventRange(from))), "no end")
        // What the calendar cannot produce is still refused rather than submitted: an end before the start, or past the
        // event window.
        assertNull(display.creatableEnd(draft("Party", from.date, LocalTime(11, 0))), "inverted")
        assertNull(display.creatableEnd(draft("Party", LocalDate(2026, 9, 6), LocalTime(13, 0))), "too long")
    }

    // The form's part of these: which step it names, and the duration once complete. How the line reads is the
    // screen's (`CreateFlowTest`).
    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The host chooses the event's date range and its end time",
        scenario = "The next missing step is named from the start",
    )
    @Verifies(
        spec = "create-event",
        requirement = "The host chooses the event's date range and its end time",
        scenario = "A name alone does not allow Create",
    )
    @Verifies(
        spec = "create-event",
        requirement = "The host chooses the event's date range and its end time",
        scenario = "Setting the end time completes the range",
    )
    fun `the form names the next missing step — then the event's duration`() {
        assertEquals(CreateStep.Name, display.create(draft("", EventRange(from))).nextStep)
        assertEquals(CreateStep.EndTime, display.create(draft("Party", EventRange(from))).nextStep)
        assertEquals(
            CreateStep.Complete(CoarseDuration.Days(3)),
            display.create(draft("Party", LocalDate(2026, 7, 9), LocalTime(12, 0))).nextStep,
        )
    }

    // The form's part: a range of five days lasts 5 days. Saying so beside what the range shares is the screen's.
    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The host chooses the event's date range and its end time",
        scenario = "The duration and the consequence are stated",
    )
    fun `a range spanning five days lasts five days`() {
        assertEquals(
            CreateStep.Complete(CoarseDuration.Days(5)),
            display.create(draft("Party", LocalDate(2026, 7, 11), LocalTime(12, 0))).nextStep,
        )
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The start follows the clock until the host chooses the range",
        scenario = "A choice in the range freezes the start",
    )
    fun `an untouched draft follows the clock and a chosen range does not`() {
        val later = LocalDateTime(2026, 7, 6, 12, 10)
        val fresh = CreateDraft.fresh(CreateDraftSession(), from)
        assertEquals(later, fresh.followNow(later).range.from)
        val chosen = fresh.choose(EventRange(from, from.date, LocalTime(13, 0), endPending = false))
        assertEquals(from, chosen.followNow(later).range.from)
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The start follows the clock until the host chooses the range",
        scenario = "A start that moved past midnight takes the last day with it",
    )
    fun `an untouched start that crosses midnight takes the last day to the new day`() {
        val lateEvening = CreateDraft.fresh(CreateDraftSession(), LocalDateTime(2026, 7, 6, 23, 58))
        val after = lateEvening.followNow(LocalDateTime(2026, 7, 7, 0, 3)).range
        assertEquals(LocalDateTime(2026, 7, 7, 0, 3), after.from)
        assertEquals(LocalDate(2026, 7, 7), after.endDay)
        assertNull(after.untilTime)
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The start follows the clock until the host chooses the range",
        scenario = "A frozen start survives a short absence",
    )
    fun `a start the host set stays through a short absence with the rest of the range`() {
        val set = CreateDraft.fresh(SHOWN, from)
            .choose(EventRange(LocalDateTime(2026, 7, 6, 15, 0), LocalDate(2026, 7, 8), LocalTime(21, 0), false))
        val back = set.afterReturn(SHOWN.activation + 1, 10.minutes, LocalDateTime(2026, 7, 6, 15, 10))
        assertEquals(set.range, back.range)
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "A long absence starts a fresh draft",
        scenario = "Coming back sooner keeps the draft",
    )
    fun `a return after 14 minutes keeps the name and the range as the host left them`() {
        val left = entered()
        val back = left.afterReturn(SHOWN.activation + 1, 14.minutes, LocalDateTime(2026, 7, 6, 18, 19))
        assertEquals(left.name to left.range, back.name to back.range)
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "A long absence starts a fresh draft",
        scenario = "Coming back after 15 minutes starts over",
    )
    fun `a return after 15 minutes starts over with an empty name and the start at now`() {
        val back = entered().afterReturn(SHOWN.activation + 1, 15.minutes, LocalDateTime(2026, 7, 6, 18, 20))
        assertEquals("", back.name)
        assertEquals(LocalDateTime(2026, 7, 6, 18, 20), back.range.from)
        assertEquals(LocalDate(2026, 7, 6), back.range.endDay)
        assertNull(back.range.untilTime)
        // ...and following the clock again.
        assertEquals(LocalDateTime(2026, 7, 6, 18, 25), back.followNow(LocalDateTime(2026, 7, 6, 18, 25)).range.from)
    }

    /** A host who typed a name and chose a range, then left the app at 18:05. */
    private fun entered() = CreateDraft.fresh(SHOWN, LocalDateTime(2026, 7, 6, 18, 4))
        .copy(name = "Party")
        .choose(EventRange(LocalDateTime(2026, 7, 6, 18, 4), LocalDate(2026, 7, 8), LocalTime(21, 0), false))

    private companion object {

        val SHOWN = CreateDraftSession(activation = 1, epoch = 0)
    }
}
