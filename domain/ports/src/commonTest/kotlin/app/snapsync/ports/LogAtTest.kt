package app.snapsync.ports

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The severity dispatch behind `Logger.invocation`, which exists so an entry point can choose a level
 * without holding a branch of its own (the `:app:*` complexity gate counts one).
 *
 * Every arm is asserted because a mis-routed one is **invisible**, not merely untidy: crash reporting
 * turns `Error`/`Assert` lines into Bugsink events and everything below them into breadcrumbs
 * (capability `crash-reporting`). An `Error` that fell through to `w` would still appear in the device
 * log, look entirely normal there, and never raise an event — so the failure it reports reaches nobody.
 * The reverse is as bad in the other direction: a `Verbose` line routed to `e` mints an event per call.
 *
 * Driven off `Severity.entries` rather than a hand-listed set, so a severity Kermit adds fails here
 * instead of silently acquiring whatever `when` arm is nearest.
 */
class LogAtTest {

    private class Capturing : LogWriter() {
        val lines: MutableList<Pair<Severity, String>> = mutableListOf()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += severity to message
        }
    }

    @Test
    fun `each severity is emitted at its own level and no other`() {
        val captured = Capturing()
        val log = Logger(StaticConfig(minSeverity = Severity.Verbose, logWriterList = listOf(captured)), "test")

        Severity.entries.forEach { severity -> log.logAt(severity) { "line-${severity.name}" } }

        assertEquals(
            Severity.entries.map { it to "line-${it.name}" },
            captured.lines,
            "a severity was emitted at the wrong level — crash reporting reads this to decide event vs breadcrumb",
        )
    }
}
