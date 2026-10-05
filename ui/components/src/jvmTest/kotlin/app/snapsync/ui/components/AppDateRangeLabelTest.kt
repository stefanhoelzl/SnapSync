package app.snapsync.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * The joined screen's range (capability `sync-status`, "The joined screen shows how long the event lasts"):
 * days across days, times on a single day, the year only when the range crosses one. It formats wall-clock
 * values and reads no clock or zone; the words around them are resources, so it is read through a composition.
 */
class AppDateRangeLabelTest {

    private val today = LocalDate(2026, 7, 13)

    private fun label(start: LocalDateTime, end: LocalDateTime?, formats: DateFormats = DateFormats("en-GB")) =
        rendered(formats) { appDateRangeLabel(start, end, today) }

    @Test
    fun `a multi-day range shows its first and last day`() {
        assertEquals("Sun 12 Jul – Tue 14 Jul", label(LocalDateTime(2026, 7, 12, 14, 0), LocalDateTime(2026, 7, 14, 22, 0)))
    }

    @Test
    fun `a range across months names both months`() {
        assertEquals("Tue 30 Jun – Thu 2 Jul", label(LocalDateTime(2026, 6, 30, 10, 0), LocalDateTime(2026, 7, 2, 10, 0)))
    }

    @Test
    fun `a range across years names both years`() {
        assertEquals(
            "Wed, 30 Dec 2026 – Sat, 2 Jan 2027",
            label(LocalDateTime(2026, 12, 30, 10, 0), LocalDateTime(2027, 1, 2, 10, 0)),
        )
    }

    @Test
    fun `a same-day event shows its times and says today when it is today`() {
        assertEquals("Today 18:00 – 23:00", label(LocalDateTime(2026, 7, 13, 18, 0), LocalDateTime(2026, 7, 13, 23, 0)))
        assertEquals("Sat 18 Jul 18:00 – 23:00", label(LocalDateTime(2026, 7, 18, 18, 0), LocalDateTime(2026, 7, 18, 23, 0)))
    }

    @Test
    fun `an end at midnight closes the day before it`() {
        // Ending "Tue 00:00" lasts through Monday: printing Tuesday would add a day nobody can take a photo in.
        assertEquals("Sun 12 Jul – Mon 13 Jul", label(LocalDateTime(2026, 7, 12, 14, 0), LocalDateTime(2026, 7, 14, 0, 0)))
        // …and a party from 18:00 to midnight is a same-day event.
        assertEquals("Today 18:00 – 00:00", label(LocalDateTime(2026, 7, 13, 18, 0), LocalDateTime(2026, 7, 14, 0, 0)))
    }

    @Test
    fun `a membership with no stored end shows only its start`() {
        assertEquals("From Sun 12 Jul", label(LocalDateTime(2026, 7, 12, 14, 0), null))
    }

    @Test
    fun `the dates follow the locale`() {
        assertEquals(
            "Mon, Jul 13 6:00 PM – 11:00 PM",
            label(LocalDateTime(2026, 7, 13, 18, 0), LocalDateTime(2026, 7, 13, 23, 0), DateFormats("en-US"))
                .let { if (it.startsWith("Today")) "Mon, Jul 13" + it.removePrefix("Today") else it },
        )
    }
}
