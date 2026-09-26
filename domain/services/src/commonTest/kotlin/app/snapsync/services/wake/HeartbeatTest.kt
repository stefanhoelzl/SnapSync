package app.snapsync.services.wake

import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    private class Capturing : LogWriter() {
        val warnings = mutableListOf<String>()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            if (severity == Severity.Warn) warnings += message
        }
    }

    @Test
    fun `arming asks for every wake - a timed heartbeat that needs the network and a library change`() {
        val wake = RecordingWake()
        Heartbeat(wake).arm()
        assertEquals(
            listOf(
                WakeId.Heartbeat to WakeTrigger.After(earliest = 60.seconds, requiresNetwork = true),
                WakeId.LibraryChanged to WakeTrigger.LibraryChange(maxDelay = 60.seconds),
            ),
            wake.scheduled,
        )
    }

    @Test
    fun `a platform without a kind of wake is not a failure`() {
        val captured = Capturing()
        val wake = RecordingWake(supported = setOf(WakeId.Heartbeat))
        Heartbeat(wake, Logger(StaticConfig(logWriterList = listOf(captured)), "test")).arm()
        assertEquals(2, wake.scheduled.size, "every wake is asked for, whatever the platform has")
        assertTrue(captured.warnings.isEmpty(), "an unsupported wake is said nothing about: ${captured.warnings}")
    }

    @Test
    fun `a refused wake is said out loud`() {
        val captured = Capturing()
        Heartbeat(RecordingWake(refuse = true), Logger(StaticConfig(logWriterList = listOf(captured)), "test")).arm()
        assertTrue(
            captured.warnings.any { "refused" in it && "BGTaskSchedulerErrorDomain/1" in it },
            "a refused heartbeat uploads only while the app is open; the line is the only evidence: ${captured.warnings}",
        )
    }

    @Test
    fun `cancelling withdraws every wake`() {
        val wake = RecordingWake(supported = setOf(WakeId.Heartbeat))
        Heartbeat(wake).cancel()
        assertEquals(WakeId.entries.toList(), wake.cancelled)
    }
}
