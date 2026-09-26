@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.snapsync.background

import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.Completion
import app.snapsync.ports.WakeHandlers
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The iOS [IosWake]'s own vocabulary (capability `background-upload`): what `WakeContract` does not state because it
 * makes no operating-system call to record — a wake iOS does not have, a task identifier the adapter does not know —
 * and the heartbeat's **refusal** path.
 *
 * A successful submit is out of reach here: `BGTaskScheduler` only accepts an identifier the process's own `Info.plist`
 * declares under `BGTaskSchedulerPermittedIdentifiers`, and a Kotlin/Native test binary has no such plist. That is a
 * permanent limitation, not a gap to fill later. The refusal is worth having anyway: a submit is rejected exactly when
 * the Kotlin identifier and the `Info.plist` entry disagree, and the OS's answer is a `false` return and an `NSError` —
 * no exception, nothing on any screen. So it must not escape as a throw, and it must be **said out loud**
 * (`docs/architecture.md`, "Absence is never silent"). `RuntimeIdentityTest` pins the identifier itself.
 */
class IosWakeTest {

    private class Capturing : LogWriter() {
        val lines: MutableList<Pair<Severity, String>> = mutableListOf()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += severity to message
        }
    }

    private class Released : Completion {
        var releases = 0
        override fun complete() {
            releases++
        }

        override fun onExpired(action: () -> Unit) = Unit
    }

    private val captured = Capturing()

    private val wake =
        IosWake(Logger(StaticConfig(minSeverity = Severity.Verbose, logWriterList = listOf(captured)), "test"))

    private val heartbeat = WakeTrigger.After(earliest = 60.seconds, requiresNetwork = true)

    @Test
    fun `a refused submit is reported rather than swallowed`() {
        // A test binary's plist permits no identifier, so the submit is refused — as on a device whose plist drifted.
        assertIs<ScheduleResult.Refused>(wake.schedule(WakeId.Heartbeat, heartbeat))
        assertTrue(
            captured.lines.any { (severity, message) ->
                severity == Severity.Warn && "BGTask submit failed" in message
            },
            "a rejected submit stops every heartbeat and raises nothing; the log line is the only evidence. " +
                "Captured: ${captured.lines}",
        )
    }

    @Test
    fun `iOS has no library-change wake`() {
        val answer = wake.schedule(WakeId.LibraryChanged, WakeTrigger.LibraryChange(60.seconds))
        assertEquals(ScheduleResult.Unsupported, answer)
        wake.cancel(WakeId.LibraryChanged)
    }

    @Test
    fun `cancelling a request that was never accepted is not an error`() {
        wake.cancel(WakeId.Heartbeat)
    }

    @Test
    fun `a task identifier the adapter does not know is completed at once`() {
        var woken = 0
        val handlers = WakeHandlers(onWake = { _, _ -> woken++ })
        // Registered through the seam's test double, never the system's: a test binary's second registration raises.
        IosWake(Logger.withTag("test"), NoRegistration).also { it.listen(handlers) }.let { adapter ->
            val completion = Released()
            adapter.onTaskLaunched("app.example.not-registered", completion)
            assertEquals(1, completion.releases, "a task held forever costs the app its future background time")
            assertEquals(0, woken, "and no wake reaches the core")

            adapter.onTaskLaunched(IosWake.HEARTBEAT_TASK_IDENTIFIER, Released())
            assertEquals(1, woken, "the heartbeat's identifier is routed to the heartbeat wake")
        }
    }

    /** A seam that accepts the registration and makes no other call. */
    private object NoRegistration : BackgroundTaskApi {
        override fun register(identifier: String, launch: (platform.BackgroundTasks.BGTask) -> Unit) = true
        override fun submit(request: platform.BackgroundTasks.BGProcessingTaskRequest): Result<Unit> =
            Result.success(Unit)
        override fun cancel(identifier: String) = Unit
        override suspend fun pendingIdentifiers(): List<String> = emptyList()
    }
}
