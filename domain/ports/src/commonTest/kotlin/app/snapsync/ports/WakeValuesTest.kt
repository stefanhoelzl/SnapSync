package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/** What the wake port speaks: a request and the operating system's answer to it. */
class WakeValuesTest {

    @Test
    fun `a wake was scheduled, refused with a detail, or is unsupported`() {
        val answers = listOf(ScheduleResult.Scheduled, ScheduleResult.Refused("too many"), ScheduleResult.Unsupported)
        assertEquals(answers.size, answers.toSet().size)
        assertEquals(ScheduleResult.Refused("too many"), ScheduleResult.Refused("too many"))
        assertNotEquals(ScheduleResult.Refused("too many"), ScheduleResult.Refused("not permitted"))
    }

    @Test
    fun `a timed wake is busy unless told otherwise`() {
        assertEquals(WakeCadence.BUSY, WakeTrigger.After(60.seconds, WakeNetwork.ANY).cadence)
    }
}
