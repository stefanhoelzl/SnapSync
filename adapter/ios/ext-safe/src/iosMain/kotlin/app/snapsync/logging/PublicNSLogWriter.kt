package app.snapsync.logging

import app.snapsync.model.LogContext

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
 * Consolidated into `:domain:logging` alongside [FileLogWriter] (capability `diagnostic-logging`,
 * D1); each line carries the ambient `[LogContext.current]` prefix for consistency with the file.
 */
class PublicNSLogWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val ctx = LogContext.current
        val line = buildString {
            if (ctx != null) append('[').append(ctx).append("] ")
            append('[').append(severity.name).append('/').append(tag).append("] ").append(message)
            if (throwable != null) append('\n').append(throwable.stackTraceToString())
        }
        NSLog(line.replace("%", "%%"))
    }
}
