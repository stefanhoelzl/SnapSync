@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.inbound

import co.touchlab.kermit.Severity
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.coroutineContext

/** One named value a log line carries. Rendered by the writer, never interpolated into the event. */
class Field(val name: String, val value: Any?)

/** `"eventId" of id` — a [Field]. */
infix fun String.of(value: Any?): Field = Field(this, value)

/**
 * One structured line: what happened ([event], a short fixed phrase) and the values it happened with ([fields]),
 * labelled with the [trigger] the work ran for — `null` when the line was written outside any (or by non-suspend
 * code, which cannot read one; a writer may still find the label its thread carries).
 */
class LogLine(
    val severity: Severity,
    val tag: String,
    val trigger: String?,
    val event: String,
    val fields: List<Field>,
    val cause: Throwable?,
)

/** Where a process's lines go. Installed once, by the process's composition. */
interface LogWriter {
    fun write(line: LogLine)
}

/**
 * The structured log (`docs/patterns.md`, "Logging"). A line is an [event] plus typed [Field]s, and it carries the
 * [Trigger] of the work that wrote it — read from the coroutine context, which is why the calls are `suspend`.
 *
 * ```
 * private val log = Log("UploadCycle")
 * log.i("top-up created jobs", "created" of n, "truncated" of truncated)
 * ```
 *
 * Non-suspend code writes through [unlabelled]: the same line, with no trigger of its own.
 */
class Log(private val tag: String) {
    suspend fun i(event: String, vararg fields: Field) = emit(Severity.Info, event, fields, null)

    suspend fun w(event: String, vararg fields: Field, cause: Throwable? = null) =
        emit(Severity.Warn, event, fields, cause)

    suspend fun e(event: String, vararg fields: Field, cause: Throwable? = null) =
        emit(Severity.Error, event, fields, cause)

    /** The same calls for code that cannot suspend. */
    val unlabelled: Unlabelled = Unlabelled(tag)

    private suspend fun emit(severity: Severity, event: String, fields: Array<out Field>, cause: Throwable?) =
        write(LogLine(severity, tag, coroutineContext[Trigger]?.label, event, fields.toList(), cause))

    /** [Log] for non-suspend code: no trigger is read. */
    class Unlabelled internal constructor(private val tag: String) {
        fun i(event: String, vararg fields: Field) = emit(Severity.Info, event, fields, null)

        fun w(event: String, vararg fields: Field, cause: Throwable? = null) = emit(Severity.Warn, event, fields, cause)

        fun e(event: String, vararg fields: Field, cause: Throwable? = null) = emit(
            Severity.Error,
            event,
            fields,
            cause,
        )

        private fun emit(severity: Severity, event: String, fields: Array<out Field>, cause: Throwable?) =
            write(LogLine(severity, tag, null, event, fields.toList(), cause))
    }

    companion object {
        private val writers = AtomicReference<List<LogWriter>>(emptyList())

        /**
         * Installs the process's writers, replacing any before. Called once per process, by its composition — the
         * one global the log has, beside Kermit's own writer list, which the old zones' lines still reach.
         */
        @CompositionOnly
        fun install(installed: List<LogWriter>) = writers.store(installed)

        private fun write(line: LogLine) = writers.load().forEach { it.write(line) }
    }
}
