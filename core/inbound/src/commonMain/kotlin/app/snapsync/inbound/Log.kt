@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.inbound

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.coroutineContext

/** One named value a log line carries. Rendered by the writer, never interpolated into the event. */
class Field(val name: String, val value: Any?) {
    companion object {
        /** `"eventId" of id` — a [Field]. */
        infix fun String.of(value: Any?): Field = Field(this, value)
    }
}

/** A line's level — the core's own, so no layer that writes or reads a line names the logging library. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * One structured line: what happened ([event], a short fixed phrase — a literal, `EventNameLiteralTest`) and the values
 * it happened with ([fields]), labelled with the [trigger] the work ran for — `null` when the line was written outside
 * any (or by non-suspend code, which cannot read one; a writer may still find the label its thread carries).
 */
class LogLine(
    val level: LogLevel,
    val tag: String,
    val trigger: String?,
    val event: String,
    val fields: List<Field>,
    val cause: Throwable?,
) {
    /** The line as text: its event, then each field as `name=value`. */
    fun text(): String = (listOf(event) + fields.map { "${it.name}=${it.value}" }).joinToString(" ")
}

/** Where a process's lines go. Installed once, by the process's composition. */
interface LogWriter {
    fun write(line: LogLine)
}

/**
 * The structured log (`docs/patterns.md`, "Logging"). A line is an [event] plus typed [Field]s, and it carries the
 * [Trigger] of the work that wrote it — read from the coroutine context, which is why the calls are `suspend`.
 *
 * ```
 * private val log = Log("TopUp")
 * log.i("top-up created jobs", "created" of n, "truncated" of truncated)
 * ```
 *
 * The event is a literal: what varies is a field, so a line reads — and a report groups — by what happened, never by
 * the values it happened with. Non-suspend code writes through [unlabelled]: the same line, with no trigger of its own.
 */
class Log(private val tag: String) {
    suspend fun i(event: String, vararg fields: Field) = emit(LogLevel.INFO, event, fields, null)

    suspend fun w(event: String, vararg fields: Field, cause: Throwable? = null) =
        emit(LogLevel.WARN, event, fields, cause)

    suspend fun e(event: String, vararg fields: Field, cause: Throwable? = null) =
        emit(LogLevel.ERROR, event, fields, cause)

    /** The same calls for code that cannot suspend. */
    val unlabelled: Unlabelled = Unlabelled(tag)

    private suspend fun emit(level: LogLevel, event: String, fields: Array<out Field>, cause: Throwable?) =
        write(LogLine(level, tag, coroutineContext[Trigger]?.label, event, fields.toList(), cause))

    /** [Log] for non-suspend code: no trigger is read. */
    class Unlabelled internal constructor(private val tag: String) {
        fun d(event: String, vararg fields: Field) = emit(LogLevel.DEBUG, event, fields, null)

        fun i(event: String, vararg fields: Field) = emit(LogLevel.INFO, event, fields, null)

        fun w(event: String, vararg fields: Field, cause: Throwable? = null) = emit(LogLevel.WARN, event, fields, cause)

        // Laid out with `emit(` on the declaration line on purpose: the coverage tool counts the default-argument stub
        // on
        // that line, and a member's day never writes an unlabelled Error without a cause (only the iOS adapters do).
        fun e(event: String, vararg fields: Field, cause: Throwable? = null) = emit(
            LogLevel.ERROR,
            event,
            fields,
            cause,
        )

        private fun emit(level: LogLevel, event: String, fields: Array<out Field>, cause: Throwable?) =
            write(LogLine(level, tag, null, event, fields.toList(), cause))
    }

    companion object {
        private val writers = AtomicReference<List<LogWriter>>(emptyList())

        /**
         * Installs the process's writers, replacing any before, and answers those it replaced. Called once per process,
         * by its composition — the one global the log has.
         */
        @CompositionOnly
        fun install(installed: List<LogWriter>): List<LogWriter> = writers.exchange(installed)

        /** [line] to the installed writers. */
        internal fun write(line: LogLine) = writers.load().forEach { it.write(line) }
    }
}
