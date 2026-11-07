package app.snapsync.data.crash

import app.snapsync.domain.crash.CrashLevel
import app.snapsync.domain.crash.LineReport
import app.snapsync.inbound.LogLevel
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.ports.ThreadLabel

/** The log's writer into the crash channel: each line as the domain says it leaves, its trigger — or the thread's. */
internal class CrashLines(private val channel: CrashChannel, private val label: ThreadLabel) : LogWriter {
    override fun write(line: LogLine) = channel.report(
        LineReport.of(
            level(line.level),
            line.tag,
            line.event,
            line.fields.map { it.name to "${it.value}" },
            line.cause,
            line.trigger ?: label.current(),
        ),
    )

    /** A line's level as the channel distinguishes it: four levels, `Assert` an error. */
    private fun level(level: LogLevel): CrashLevel = when (level) {
        LogLevel.VERBOSE, LogLevel.DEBUG -> CrashLevel.DEBUG
        LogLevel.INFO -> CrashLevel.INFO
        LogLevel.WARN -> CrashLevel.WARNING
        LogLevel.ERROR, LogLevel.ASSERT -> CrashLevel.ERROR
    }
}
