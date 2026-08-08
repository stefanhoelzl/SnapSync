package app.snapsync.logging

import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The format string this writer hands `NSLog` (capability `diagnostic-logging`).
 *
 * The class exists to defeat os_log's `<private>` redaction, and the trick it uses is to put the
 * already-formatted message in `NSLog`'s **format-string** position — the one part os_log does not
 * redact. That trick is only safe because every literal `%` is doubled first: `NSLog` reads that
 * argument as printf, so an undoubled `%s` or `%@` in a log message (a URL with an escape, a
 * serialized payload, a percent-encoded filename — all of which this app logs) makes it consume a
 * variadic argument that was never passed. The result is garbage in the syslog at best and a fault at
 * worst, on the debugging path, from data the app was merely trying to describe.
 *
 * `NSLog` writes to the unified log and nothing can read it back, so the format string is asserted
 * where it is built.
 */
class PublicNSLogWriterTest {

    @Test
    fun `every percent is doubled so NSLog cannot read the message as a format string`() {
        val line = publicNSLogFormatString(Severity.Info, "GET /files?q=a%20b took 50%", "http", null)

        assertTrue("a%%20b" in line, "an undoubled %% is a printf specifier: $line")
        assertTrue("50%%" in line, "a trailing percent is a specifier too: $line")
        assertTrue(
            "%" !in line.replace("%%", ""),
            "a lone percent is a specifier waiting for an argument that was never passed: $line",
        )
    }

    @Test
    fun `an ObjC specifier in a message is neutralised`() {
        val line = publicNSLogFormatString(Severity.Warn, "unexpected %@ in payload", "engine", null)

        assertTrue(
            "%%@" in line,
            "%@ tells NSLog to read an object pointer that was never passed: $line",
        )
    }

    @Test
    fun `the line carries its severity and tag and message`() {
        assertEquals(
            "[Info/gallery] enumerated 3 resources",
            publicNSLogFormatString(Severity.Info, "enumerated 3 resources", "gallery", null),
        )
    }

    @Test
    fun `the ambient entry point prefixes the line`() {
        val owned = LogContext.enter("onSilentPush")
        try {
            assertEquals(
                "[onSilentPush] [Warn/download] reconcile failed",
                publicNSLogFormatString(Severity.Warn, "reconcile failed", "download", null),
            )
        } finally {
            LogContext.exit(owned)
        }
    }

    @Test
    fun `a throwable follows the message on its own line`() {
        val line = publicNSLogFormatString(Severity.Error, "upload failed", "engine", IllegalStateException("boom"))

        assertTrue(line.startsWith("[Error/engine] upload failed\n"), "unexpected line: $line")
        assertTrue("boom" in line)
    }
}
