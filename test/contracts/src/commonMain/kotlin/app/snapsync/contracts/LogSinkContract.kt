package app.snapsync.contracts

import app.snapsync.ports.LogSink
import co.touchlab.kermit.Severity
import kotlin.test.assertTrue

/** Where a sink's lines go, as far as a clause can read them. */
enum class LogSinkState {
    /** A sink writing to a file the binding can read back ([WrittenLog.read]), empty so far. */
    READABLE,
}

/** The sink, and what it has written so far. */
class WrittenLog(val sink: LogSink, val read: () -> String)

/**
 * What a log sink promises (`docs/architecture.md`, "Logging"): every line it is handed is written, verbatim — the
 * device log is the canonical un-redacted channel, so a line that is dropped or altered is lost evidence.
 */
object LogSinkContract : Contract<LogSinkState, WrittenLog>("LogSink") {

    override val clauses = clauses {

        clause(
            "READABLE_EVERY_LINE_IS_WRITTEN_VERBATIM",
            LogSinkState.READABLE,
            covers = cells { on<LogSink>().answers(LogSink::write).returns() },
        ) { log ->
            val first = "event 0b5c2e1a-0000-4000-8000-000000000001 joined"
            val second = "the second line, after the first"
            log.sink.write(Severity.Info, "contract", first)
            log.sink.write(Severity.Warn, "contract", second)
            val written = log.read()
            assertTrue(first in written, "a line is written whole, ids intact: $written")
            assertTrue(written.indexOf(second) > written.indexOf(first), "in the order it was handed")
        }
    }
}
