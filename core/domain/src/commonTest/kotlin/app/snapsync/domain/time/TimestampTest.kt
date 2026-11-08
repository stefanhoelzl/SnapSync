package app.snapsync.domain.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TimestampTest {

    @Test
    fun `a timestamp is its instant truncated to the second — toward the earlier instant`() {
        assertEquals(
            Instant.parse("2026-07-06T14:32:11Z"),
            Timestamp.of(Instant.parse("2026-07-06T14:32:11.987654Z")).at,
        )
        assertEquals(Instant.parse("1969-12-31T23:59:59Z"), Timestamp.of(Instant.parse("1969-12-31T23:59:59.5Z")).at)
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), Timestamp.of(Instant.parse("2026-01-02T03:04:05Z")).at)
    }

    @Test
    fun `timestamps compare as instants`() {
        val at = Timestamp.of(Instant.parse("2026-07-06T14:32:11Z"))
        assertTrue(Timestamp.of(Instant.parse("2026-07-06T14:32:12Z")) > at)
        assertTrue(Timestamp.of(Instant.parse("2026-07-06T16:32:11+02:00")).compareTo(at) == 0)
        assertTrue(Timestamp.of(Instant.parse("2026-07-06T14:32:10Z")) < at)
    }

    @Test
    fun `the time between two timestamps`() {
        val from = Timestamp.of(Instant.parse("2026-07-06T14:32:11Z"))
        val until = Timestamp.of(Instant.parse("2026-07-06T14:33:12Z"))
        assertEquals(61.seconds, until - from)
        assertEquals((-61).seconds, from - until)
    }
}
