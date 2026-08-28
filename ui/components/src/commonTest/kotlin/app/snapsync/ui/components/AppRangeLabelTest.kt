package app.snapsync.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime

/**
 * The range + duration formatting the create/join surfaces render (capability `photo-selection-policy`):
 * compact-adaptive and pure: it formats two wall-clock values and reads no clock or zone, which is
 * why it belongs to the design system rather than to the reduction that decides what the values ARE.
 */
class AppRangeLabelTest {

    @Test
    fun `a same-day range collapses to one day with a time span`() {
        assertEquals(
            "14 Jul, 18:00–23:00",
            appRangeLabel(LocalDateTime(2026, 7, 14, 18, 0), LocalDateTime(2026, 7, 14, 23, 0)),
        )
    }

    @Test
    fun `a multi-day whole-day range shows just the day span`() {
        assertEquals(
            "14–21 Jul 2026",
            appRangeLabel(LocalDateTime(2026, 7, 14, 0, 0), LocalDateTime(2026, 7, 21, 0, 0)),
        )
    }

    @Test
    fun `a multi-day range with times shows both endpoints in full`() {
        assertEquals(
            "14 Jul 18:00 – 21 Jul 23:00",
            appRangeLabel(LocalDateTime(2026, 7, 14, 18, 0), LocalDateTime(2026, 7, 21, 23, 0)),
        )
    }

}
