package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The device-log stamp's text, pinned against known instants (capability `privacy-security`).
 *
 * The writer used to build this text from `NSDate.description` (UTC, `yyyy-MM-dd HH:mm:ss +0000`) with
 * the milliseconds spliced in ahead of the zone; [utcLogStamp] produces it arithmetically, so what has to
 * hold is that the calendar arithmetic never drifts from the civil date. The expected strings were
 * computed independently (Python's `datetime`, UTC). The cases are the ones calendar arithmetic gets
 * wrong: the leap day of a leap year and of a leap century (2000), the missing leap day of a non-leap
 * century (2100), and the last millisecond of a day, a month and a year rolling into the next.
 *
 * What this cannot pin is Foundation itself — that `NSDate.description` prints exactly this shape.
 * That one check runs on an iOS host, in `FileLogWriterTest`.
 */
class LogStampTest {

    private fun stamps(vararg cases: Pair<Long, String>) {
        for ((millis, expected) in cases) assertEquals(expected, utcLogStamp(millis), "epochMillis=$millis")
    }

    @Test
    fun `the epoch and millisecond padding`() = stamps(
        0L to "1970-01-01 00:00:00.000 +0000",
        7L to "1970-01-01 00:00:00.007 +0000",
        1_790_172_309_123L to "2026-09-23 14:05:09.123 +0000",
        2_147_483_648_000L to "2038-01-19 03:14:08.000 +0000",
    )

    @Test
    fun `a leap century keeps its leap day`() = stamps(
        951_825_600_050L to "2000-02-29 12:00:00.050 +0000",
        951_868_800_000L to "2000-03-01 00:00:00.000 +0000",
    )

    @Test
    fun `an ordinary leap year rolls from its leap day into March`() = stamps(
        1_709_251_199_999L to "2024-02-29 23:59:59.999 +0000",
        1_709_251_200_000L to "2024-03-01 00:00:00.000 +0000",
    )

    @Test
    fun `a common year rolls from February 28 into March`() = stamps(
        1_677_628_799_999L to "2023-02-28 23:59:59.999 +0000",
        1_677_628_800_000L to "2023-03-01 00:00:00.000 +0000",
    )

    @Test
    fun `a non-leap century has no leap day`() = stamps(
        4_107_542_399_999L to "2100-02-28 23:59:59.999 +0000",
        4_107_542_400_000L to "2100-03-01 00:00:00.000 +0000",
    )

    @Test
    fun `a month and a year roll over at their last millisecond`() = stamps(
        1_777_593_599_999L to "2026-04-30 23:59:59.999 +0000",
        946_684_799_999L to "1999-12-31 23:59:59.999 +0000",
        946_684_800_000L to "2000-01-01 00:00:00.000 +0000",
        1_735_689_599_999L to "2024-12-31 23:59:59.999 +0000",
        1_735_689_600_000L to "2025-01-01 00:00:00.000 +0000",
    )
}
