package app.snapsync.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * The joined screen's range (capability `sync-status`, "The joined screen shows how long the event lasts"):
 * days across days, times on a single day, the year only when the range crosses one. Pure: it formats
 * wall-clock values and reads no clock or zone.
 */
class AppDateRangeLabelTest {

    private val today = LocalDate(2026, 7, 13)

    @Test
    fun `a multi-day range shows its first and last day`() {
        assertEquals(
            "Sun 12 – Tue 14 Jul",
            appDateRangeLabel(LocalDateTime(2026, 7, 12, 14, 0), LocalDateTime(2026, 7, 14, 22, 0), today),
        )
    }

    @Test
    fun `a range across months names both months`() {
        assertEquals(
            "Tue 30 Jun – Thu 2 Jul",
            appDateRangeLabel(LocalDateTime(2026, 6, 30, 10, 0), LocalDateTime(2026, 7, 2, 10, 0), today),
        )
    }

    @Test
    fun `a range across years names both years`() {
        assertEquals(
            "Wed 30 Dec 2026 – Sat 2 Jan 2027",
            appDateRangeLabel(LocalDateTime(2026, 12, 30, 10, 0), LocalDateTime(2027, 1, 2, 10, 0), today),
        )
    }

    @Test
    fun `a same-day event shows its times and says today when it is today`() {
        assertEquals(
            "Today 18:00 – 23:00",
            appDateRangeLabel(LocalDateTime(2026, 7, 13, 18, 0), LocalDateTime(2026, 7, 13, 23, 0), today),
        )
        assertEquals(
            "Sat 18 Jul 18:00 – 23:00",
            appDateRangeLabel(LocalDateTime(2026, 7, 18, 18, 0), LocalDateTime(2026, 7, 18, 23, 0), today),
        )
    }

    @Test
    fun `an end at midnight closes the day before it`() {
        // Ending "Tue 00:00" lasts through Monday: printing Tuesday would add a day nobody can take a photo in.
        assertEquals(
            "Sun 12 – Mon 13 Jul",
            appDateRangeLabel(LocalDateTime(2026, 7, 12, 14, 0), LocalDateTime(2026, 7, 14, 0, 0), today),
        )
        // …and a party from 18:00 to midnight is a same-day event.
        assertEquals(
            "Today 18:00 – 00:00",
            appDateRangeLabel(LocalDateTime(2026, 7, 13, 18, 0), LocalDateTime(2026, 7, 14, 0, 0), today),
        )
    }

    @Test
    fun `a membership with no stored end shows only its start`() {
        assertEquals("From Sun 12 Jul", appDateRangeLabel(LocalDateTime(2026, 7, 12, 14, 0), null, today))
    }
}
