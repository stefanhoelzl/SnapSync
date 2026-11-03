package app.snapsync.data.wake

import app.snapsync.domain.wake.HeartbeatCadence
import app.snapsync.domain.wake.HeartbeatNetwork
import app.snapsync.domain.wake.HeartbeatTrigger
import app.snapsync.domain.wake.LibraryWatch
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.ScheduleResult
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeCadence
import app.snapsync.ports.WakeHandlers
import app.snapsync.ports.WakeId
import app.snapsync.ports.WakeNetwork
import app.snapsync.ports.WakeTrigger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The wakes over the `Wake` port: what each request asks for, that every wake is asked for on every platform — one the
 * platform lacks is answered unsupported and needs no branch — and that a refusal is answered as one.
 */
class WakeArmingTest {

    /** A platform that has [supported] wakes, answering [refusal] to every request when set, and recording each call. */
    private class RecordingWake(
        private val supported: Set<WakeId> = WakeId.entries.toSet(),
        private val refusal: String? = null,
    ) : Wake {
        val scheduled = mutableListOf<Pair<WakeId, WakeTrigger>>()
        val cancelled = mutableListOf<WakeId>()

        @CompositionOnly
        override fun inbound(handlers: WakeHandlers) = Unit

        override suspend fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult {
            scheduled += id to trigger
            return when {
                id !in supported -> ScheduleResult.Unsupported
                refusal != null -> ScheduleResult.Refused(refusal)
                else -> ScheduleResult.Scheduled
            }
        }

        override fun cancel(id: WakeId) {
            cancelled += id
        }
    }

    @Test
    fun `a heartbeat asks for a timed wake at its cadence and network`() = runTest {
        val wake = RecordingWake()
        val busy = HeartbeatTrigger(60.seconds, HeartbeatNetwork.UNRESTRICTED, HeartbeatCadence.BUSY)
        assertEquals(WakeRequest.Standing, WakeArming(wake).arm(busy))
        WakeArming(wake).arm(HeartbeatTrigger(1.hours, HeartbeatNetwork.ANY, HeartbeatCadence.IDLE))
        assertEquals<List<Pair<WakeId, WakeTrigger>>>(
            listOf(
                WakeId.Heartbeat to WakeTrigger.After(60.seconds, WakeNetwork.UNRESTRICTED, WakeCadence.BUSY),
                WakeId.Heartbeat to WakeTrigger.After(1.hours, WakeNetwork.ANY, WakeCadence.IDLE),
            ),
            wake.scheduled,
        )
    }

    @Test
    fun `a refused heartbeat is answered refused`() = runTest {
        val trigger = HeartbeatTrigger(60.seconds, HeartbeatNetwork.ANY, HeartbeatCadence.BUSY)
        assertEquals(
            WakeRequest.Refused("BGTaskSchedulerErrorDomain/1"),
            WakeArming(RecordingWake(refusal = "BGTaskSchedulerErrorDomain/1")).arm(trigger),
        )
        assertEquals(WakeRequest.Unsupported, WakeArming(RecordingWake(supported = emptySet())).arm(trigger))
    }

    @Test
    fun `watching the library stands where the platform has the wake, and nowhere else`() = runTest {
        val wake = RecordingWake()
        assertEquals(LibraryWatch.STANDING, WakeArming(wake).watchLibrary())
        assertEquals(WakeId.LibraryChanged to WakeTrigger.LibraryChange(60.seconds), wake.scheduled.single())
        assertEquals(LibraryWatch.NONE, WakeArming(RecordingWake(supported = setOf(WakeId.Heartbeat))).watchLibrary())
        assertEquals(LibraryWatch.NONE, WakeArming(RecordingWake(refusal = "refused")).watchLibrary())
    }

    @Test
    fun `cancelling withdraws every wake`() {
        val wake = RecordingWake()
        WakeArming(wake).cancelAll()
        assertEquals(WakeId.entries.toList(), wake.cancelled)
    }
}
