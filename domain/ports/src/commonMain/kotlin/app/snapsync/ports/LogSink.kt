package app.snapsync.ports

import co.touchlab.kermit.Severity

/**
 * Where a process's log lines are written — ONE external system each: the device-log file, the platform's unified
 * log (capability `privacy-security`).
 *
 * [line] is the whole formatted line — entry-point prefix, severity and tag, message, throwable — already built by
 * the process's log writer (`services/logs`); [severity] and [tag] ride beside it for a sink whose platform files
 * lines by level or tag. How the line is stored — a descriptor held open, the roll to a `.1` sibling, the stamp — is
 * the adapter's (the iOS file sink is tuned for background wakes), and a sink never throws: this IS the log, so
 * there is nowhere to report a failure.
 */
fun interface LogSink {
    fun write(severity: Severity, tag: String, line: String)
}
