package app.snapsync.ios.upload

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import platform.Foundation.NSLog

/**
 * A Kermit writer whose messages are **not** redacted as `<private>` in the unified log / device
 * syslog. The redaction only applies to os_log *arguments* (`%@`/`%s`), not to the format string
 * itself — so this puts the whole (already-formatted) message in the format-string position, with
 * every literal `%` doubled to `%%` so nothing is interpreted as a specifier. Use only on the test
 * device path; it makes log content world-readable, which is the point here.
 *
 * (Intentionally duplicated in `:app:ios`'s iosMain rather than hoisted into a shared core module —
 * both leaf wiring modules already depend on Kermit, and neither shared module does.)
 */
internal class PublicNSLogWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val line = buildString {
            append('[').append(severity.name).append('/').append(tag).append("] ").append(message)
            if (throwable != null) append('\n').append(throwable.stackTraceToString())
        }
        NSLog(line.replace("%", "%%"))
    }
}
