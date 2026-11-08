package app.snapsync.logging

import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLevel
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter

/**
 * A log writer that keeps every line it is handed — what a test asserts a unit's logging by. [install] makes it the
 * process's one writer. One per test module: a test source set reaches no other module's.
 */
class CapturingLogWriter : LogWriter {
    val lines: MutableList<LogLine> = mutableListOf()

    /** Each line as its level and its text — event, then fields. */
    val written: List<Pair<LogLevel, String>> get() = lines.map { it.level to it.text() }

    override fun write(line: LogLine) {
        lines += line
    }

    /** This writer, as the process's only one. */
    @OptIn(CompositionOnly::class)
    fun install(): CapturingLogWriter = also { Log.install(listOf(it)) }
}
