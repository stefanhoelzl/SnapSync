package app.snapsync.data.logs

import app.snapsync.format.DeviceLogText
import app.snapsync.inbound.LogLevel
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.ports.LogSink
import app.snapsync.ports.ThreadLabel

/**
 * Every line to the process's [sinks] — the device-log file and the platform's log — rendered ONCE in the device log's
 * text, with its trigger, or the one the calling thread is labelled with when it was written by code that could not
 * read one (a non-suspend caller, or a Kermit line through the bridge).
 */
class SinkLines(private val sinks: List<LogSink>, private val label: ThreadLabel) : LogWriter {
    override fun write(line: LogLine) {
        val body = DeviceLogText.body(
            line.trigger ?: label.current(),
            word(line.level),
            line.tag,
            line.text(),
            line.cause,
        )
        sinks.forEach { it.write(line.level, line.tag, body) }
    }

    /** A level as the device log has always written it — `[Info/tag]` — so a reader's searches keep working. */
    private fun word(level: LogLevel): String = when (level) {
        LogLevel.VERBOSE -> "Verbose"
        LogLevel.DEBUG -> "Debug"
        LogLevel.INFO -> "Info"
        LogLevel.WARN -> "Warn"
        LogLevel.ERROR -> "Error"
        LogLevel.ASSERT -> "Assert"
    }
}
