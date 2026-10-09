package app.snapsync.presentation

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
import kotlin.time.Instant

/** The create form's rules (capability `create-event`): when Create is offered, and what the line above it says. */
class CreateDraftTest {

    private val clock = CutoffFormatter(now = { Instant.parse("2026-07-06T12:00:00Z") }, zone = TimeZone.UTC)
    private val display = Display(clock) { _, _, _ -> error("the create form resolves no event window") }
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

    @Test
    fun `the form names the next missing step — then the event's duration`() {
        assertEquals(CreateStep.Name, display.create(draft("", EventRange(from))).nextStep)
        assertEquals(CreateStep.EndTime, display.create(draft("Party", EventRange(from))).nextStep)
        assertEquals(
            CreateStep.Complete(CoarseDuration.Days(3)),
            display.create(draft("Party", LocalDate(2026, 7, 9), LocalTime(12, 0))).nextStep,
        )
    }

    @Test
    fun `an untouched draft follows the clock and a chosen range does not`() {
        val later = LocalDateTime(2026, 7, 6, 12, 10)
        val fresh = CreateDraft.fresh(CreateDraftSession(), from)
        assertEquals(later, fresh.followNow(later).range.from)
        val chosen = fresh.choose(EventRange(from, from.date, LocalTime(13, 0), endPending = false))
        assertEquals(from, chosen.followNow(later).range.from)
    }
}
