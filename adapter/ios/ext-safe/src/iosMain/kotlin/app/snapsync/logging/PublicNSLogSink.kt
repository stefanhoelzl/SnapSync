package app.snapsync.logging

import app.snapsync.ports.LogSink
import co.touchlab.kermit.Severity
import platform.Foundation.NSLog

/**
 * The unified-log [LogSink], whose lines are **not** redacted as `<private>` in the unified log / device syslog.
 * os_log only redacts *arguments* (`%@`/`%s`), not the format string itself — so this puts the whole
 * (already-formatted) line in the format-string position, with every literal `%` doubled to `%%` so nothing is
 * interpreted as a specifier. Use only on the test device path; it makes log content world-readable, which is the
 * point here.
 *
 * Beside [FileLogSink] in `:adapter:ios:ext-safe` (capability `privacy-security`): both write the same formatted
 * line, entry-point prefix included.
 */
class PublicNSLogSink : LogSink {
    override fun write(severity: Severity, tag: String, line: String) = NSLog(publicNSLogFormatString(line))
}

/**
 * The exact string handed to `NSLog` as its **format string**.
 *
 * Split out of [PublicNSLogSink.write] so it is observable: `NSLog` writes to the unified log, which no test can read
 * back, and the `%` doubling is not cosmetic. `NSLog` reads its first argument as a printf format, and this sink
 * deliberately puts arbitrary already-formatted log text there — so a line that happens to contain `%s` or `%@` (a
 * URL with an escape, a serialized payload) would make `NSLog` consume a variadic argument that was never passed,
 * printing garbage or faulting. Doubling every literal `%` is what makes putting the line in that position safe, and
 * it is the only reason this sink can bypass os_log's `<private>` redaction at all.
 */
internal fun publicNSLogFormatString(line: String): String = line.replace("%", "%%")
