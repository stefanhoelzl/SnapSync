package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** The counts line's value (capability `sync-status`): complete when every photo went through. */
class SyncCountsTest {

    @Test
    fun `a direction is complete once done meets total`() {
        assertFalse(DirectionCount.Progress(12, 15).complete)
        assertTrue(DirectionCount.Progress(15, 15).complete)
        assertTrue(DirectionCount.Progress(0, 0).complete)
    }

    @Test
    fun `counts and timing survive the control channel's wire`() {
        val counts = SyncCounts(DirectionCount.Off, DirectionCount.Progress(40, 52))
        assertEquals(counts, Json.decodeFromString<SyncCounts>(Json.encodeToString(counts)))
        val timings = listOf(
            EventTiming.Upcoming(TimeLeft.Days(2)),
            EventTiming.Running(TimeLeft.Hours(5)),
            EventTiming.Running(TimeLeft.Minutes(40)),
            EventTiming.Running(TimeLeft.UnderAMinute),
            EventTiming.Running(remaining = null),
            EventTiming.Ended,
        )
        for (timing in timings) {
            assertEquals(timing, Json.decodeFromString<EventTiming>(Json.encodeToString(timing)))
        }
    }
}
