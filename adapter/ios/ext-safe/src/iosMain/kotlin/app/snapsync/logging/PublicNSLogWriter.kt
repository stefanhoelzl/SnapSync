package app.snapsync.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import platform.Foundation.NSLog

/**
 * A Kermit writer whose messages are **not** redacted as `<private>` in the unified log / device
 * syslog. os_log only redacts *arguments* (`%@`/`%s`), not the format string itself — so this puts
 * the whole (already-formatted) message in the format-string position, with every literal `%`
 * doubled to `%%` so nothing is interpreted as a specifier. Use only on the test device path; it
 * makes log content world-readable, which is the point here.
 *
 * Consolidated into `:adapter:ios:ext-safe` alongside [FileLogWriter] (capability `diagnostic-logging`,
 * D1); each line carries the ambient `[LogContext.current]` prefix for consistency with the file.
 */
class PublicNSLogWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) =
        NSLog(publicNSLogFormatString(severity, message, tag, throwable))
}

/**
 * The exact string handed to `NSLog` as its **format string**.
 *
 * Split out of [PublicNSLogWriter.log] so it is observable: `NSLog` writes to the unified log, which
 * no test can read back, and the `%` doubling is not cosmetic. `NSLog` reads its first argument as a
 * printf format, and this writer deliberately puts arbitrary already-formatted log text there — so a
 * message that happens to contain `%s` or `%@` (a URL with an escape, a serialized payload) would make
 * `NSLog` consume a variadic argument that was never passed, printing garbage or faulting. Doubling
 * every literal `%` is what makes putting the message in that position safe, and it is the only reason
 * this class can bypass os_log's `<private>` redaction at all.
 */
internal fun publicNSLogFormatString(
    severity: Severity,
    message: String,
    tag: String,
    throwable: Throwable?,
): String {
    val ctx = LogContext.current
    val line = buildString {
        if (ctx != null) append('[').append(ctx).append("] ")
        append('[').append(severity.name).append('/').append(tag).append("] ").append(message)
        if (throwable != null) append('\n').append(throwable.stackTraceToString())
    }
    return line.replace("%", "%%")
}
