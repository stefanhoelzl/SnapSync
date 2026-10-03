package app.snapsync.services.wake

import app.snapsync.model.ScheduleResult
import app.snapsync.model.TransferNetwork
import app.snapsync.model.WakeCadence
import app.snapsync.model.WakeId
import app.snapsync.model.WakeNetwork
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import app.snapsync.services.CapturingLogWriter
import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The heartbeat over the `Wake` port (capability `background-upload`): what it asks for, that it asks for every wake
 * on every platform — a wake the platform lacks is answered `Unsupported` and needs no branch — and that a refusal is
 * said out loud.
 */
class HeartbeatTest {

    /** A platform that has [supported] wakes, refusing the heartbeat when [refuse] is set, and recording every call. */
    private class RecordingWake(
        private val supported: Set<WakeId> = WakeId.entries.toSet(),
        private val refuse: Boolean = false,
    ) : Wake {
        val scheduled = mutableListOf<Pair<WakeId, WakeTrigger>>()
        val cancelled = mutableListOf<WakeId>()

        override fun listen(handlers: WakeHandlers) = Unit

        override fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult {
            scheduled += id to trigger
            return when {
                id !in supported -> ScheduleResult.Unsupported
                refuse -> ScheduleResult.Refused("BGTaskSchedulerErrorDomain/1")
                else -> ScheduleResult.Scheduled
            }
        }

        override fun cancel(id: WakeId) {
            cancelled += id
        }
    }

    private fun CapturingLogWriter.warnings() = lines.filter { it.first == Severity.Warn }.map { it.second }

    @Test
    fun `a busy heartbeat asks for a timed wake after a minute that needs the network`() {
        val wake = RecordingWake()
        Heartbeat(wake).arm(WakeCadence.BUSY)
        assertEquals(
            listOf<Pair<WakeId, WakeTrigger>>(
                WakeId.Heartbeat to WakeTrigger.After(earliest = 60.seconds, network = WakeNetwork.ANY, cadence = WakeCadence.BUSY),
            ),
            wake.scheduled,
        )
    }

    @Test
    fun `an idle heartbeat asks for a timed wake after an hour that needs the network`() {
        val wake = RecordingWake()
        Heartbeat(wake).arm(WakeCadence.IDLE)
        assertEquals(
            listOf<Pair<WakeId, WakeTrigger>>(
                WakeId.Heartbeat to WakeTrigger.After(earliest = 1.hours, network = WakeNetwork.ANY, cadence = WakeCadence.IDLE),
            ),
            wake.scheduled,
        )
    }

    /**
     * Capability `mobile-data`: a member who keeps photos off mobile data gets a busy heartbeat that waits for an
     * unrestricted network — the transfers it runs would wait anyway, and on Android it is what resumes them on Wi-Fi —
     * while an idle one, which moves no photo, keeps waiting for any connection.
     */
    @Test
    fun `with photos kept off mobile data a busy heartbeat waits for an unrestricted network and an idle one does not`() {
        val wake = RecordingWake()
        val heartbeat = Heartbeat(wake, transferNetwork = { TransferNetwork.UNRESTRICTED_ONLY })
        heartbeat.arm(WakeCadence.BUSY)
        heartbeat.arm(WakeCadence.IDLE)
        assertEquals(
            listOf(WakeNetwork.UNRESTRICTED, WakeNetwork.ANY),
            wake.scheduled.map { (it.second as WakeTrigger.After).network },
        )
    }

    @Test
    fun `watching the library asks for a library-change wake and answers that one stands`() {
        val wake = RecordingWake()
        assertTrue(Heartbeat(wake).watchLibrary())
        assertEquals(listOf<Pair<WakeId, WakeTrigger>>(WakeId.LibraryChanged to WakeTrigger.LibraryChange(maxDelay = 60.seconds)), wake.scheduled)
    }

    @Test
    fun `a platform without a library-change wake is not a failure and watches nothing`() {
        val captured = CapturingLogWriter()
        val wake = RecordingWake(supported = setOf(WakeId.Heartbeat))
        assertFalse(Heartbeat(wake, log = captured.logger()).watchLibrary())
        assertTrue(captured.warnings().isEmpty(), "an unsupported wake is said nothing about: ${captured.warnings()}")
    }

    @Test
    fun `a refused library watch watches nothing`() {
        assertFalse(Heartbeat(RecordingWake(refuse = true)).watchLibrary())
    }

    @Test
    fun `a refused wake is said out loud`() {
        val captured = CapturingLogWriter()
        Heartbeat(RecordingWake(refuse = true), log = captured.logger()).arm(WakeCadence.BUSY)
        assertTrue(
            captured.warnings().any { "refused" in it && "BGTaskSchedulerErrorDomain/1" in it },
            "a refused heartbeat uploads only while the app is open; the line is the only evidence: ${captured.warnings()}",
        )
    }

    @Test
    fun `cancelling withdraws every wake`() {
        val wake = RecordingWake(supported = setOf(WakeId.Heartbeat))
        Heartbeat(wake).cancel()
        assertEquals(WakeId.entries.toList(), wake.cancelled)
    }
}
