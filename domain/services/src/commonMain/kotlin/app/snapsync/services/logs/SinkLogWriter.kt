package app.snapsync.services.logs

import app.snapsync.model.logLineBody
import app.snapsync.ports.EntryContext
import app.snapsync.ports.LogSink
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity

/**
 * The logging seam onto the process's [LogSink]s (capability `privacy-security`): every line, formatted ONCE
 * ([logLineBody]) with the entry point that triggered it, handed to each sink.
 */
class SinkLogWriter(private val sinks: List<LogSink>, private val entry: EntryContext) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val line = logLineBody(entry.current(), severity.name, tag, message, throwable)
        sinks.forEach { it.write(severity, tag, line) }
    }
}
