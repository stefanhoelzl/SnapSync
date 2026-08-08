package app.snapsync.ios.urlsession

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The iOS 18–26.0 heartbeat's **refusal** path (capability `ios-url-session-upload`).
 *
 * A successful submit is out of reach here: `BGTaskScheduler` only accepts an identifier the process's
 * own `Info.plist` declares under `BGTaskSchedulerPermittedIdentifiers`, and a Kotlin/Native test
 * binary has no such plist. That is a permanent limitation, not a gap to fill later.
 *
 * The refusal is worth having anyway, because it is not hypothetical: a submit is rejected exactly
 * when the Kotlin identifier and the `Info.plist` entry disagree, which is a one-character edit away
 * at all times, and the OS's answer to it is a `false` return and an `NSError` — no exception, no
 * crash, nothing on any screen. The whole tier simply stops waking, and the app looks idle rather
 * than broken. So the two things asserted are that the refusal does not escape as a throw (which
 * would take down whichever entry point re-armed the heartbeat) and that it is **said out loud**
 * (`module-architecture`, "Absence is never silent").
 *
 * `RuntimeIdentityTest` pins the identifier itself against `Info.plist`, from the JVM. This covers
 * what happens on the device when something else goes wrong.
 */
class IosBackgroundSchedulerTest {

    private class Capturing : LogWriter() {
        val lines: MutableList<Pair<Severity, String>> = mutableListOf()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += severity to message
        }
    }

    private val captured = Capturing()

    private val scheduler = IosBackgroundScheduler(
        log = Logger(StaticConfig(minSeverity = Severity.Verbose, logWriterList = listOf(captured)), "test"),
        taskIdentifier = "app.snapsync.test.unregistered",
    )

    @Test
    fun `a refused submit is reported rather than swallowed`() {
        scheduler.scheduleNext()

        assertTrue(
            captured.lines.any { (severity, message) ->
                severity == Severity.Warn && "BGTask submit failed" in message
            },
            "a rejected submit kills the whole 18-26.0 upload tier and raises nothing; the log line is " +
                "the only evidence it ever produces. Captured: ${captured.lines}",
        )
    }

    /** And it must not throw: the caller is an OS entry point re-arming the heartbeat. */
    @Test
    fun `a refused submit does not escape as an exception`() {
        scheduler.scheduleNext()
        scheduler.scheduleNext()
    }

    @Test
    fun `cancelling a request that was never accepted is not an error`() {
        scheduler.cancel()
    }
}
