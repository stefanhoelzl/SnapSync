package app.snapsync.services.logs

import app.snapsync.inbound.LogLine
import app.snapsync.model.logLineBody
import app.snapsync.ports.LogSink
import app.snapsync.ports.ThreadLabel
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity

/**
 * The logging seam onto the process's [LogSink]s for a Kermit line: formatted ONCE ([logLineBody]) with the trigger
 * the calling thread is labelled with, handed to each sink.
 */
class SinkLogWriter(private val sinks: List<LogSink>, private val label: ThreadLabel) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val line = logLineBody(label.current(), severity.name, tag, message, throwable)
        sinks.forEach { it.write(severity, tag, line) }
    }
}

/**
 * The same seam for a structured line (`app.snapsync.inbound.Log`): the same text — its trigger, or the thread's when
 * it was written by code that could not read one — with its fields rendered after the event.
 */
class SinkLines(private val sinks: List<LogSink>, private val label: ThreadLabel) : app.snapsync.inbound.LogWriter {
    override fun write(line: LogLine) {
        val body = logLineBody(
            line.trigger ?: label.current(),
            line.severity.name,
            line.tag,
            line.message(),
            line.cause,
        )
        sinks.forEach { it.write(line.severity, line.tag, body) }
    }
}

/** A structured line's text: its event, then each field as `name=value`. */
fun LogLine.message(): String = (listOf(event) + fields.map { "${it.name}=${it.value}" }).joinToString(" ")
