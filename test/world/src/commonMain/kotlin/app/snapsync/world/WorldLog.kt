package app.snapsync.world

import co.touchlab.kermit.Logger
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit

/**
 * The world's log inspection list (capability `harness-world-model`, "Rigging cannot live in a fake") —
 * what the composed graph wrote, and at what severity.
 *
 * The severity is the point. Kermit's `Error`/`Assert` lines become crash-reporting **events** while
 * lower ones ride as breadcrumbs (capability `crash-reporting`), so "reported at `Error`" is a contract
 * some features have — and for a feature that deliberately writes nothing else, the log is the only
 * observable it has. Anything asserting on messages is asserting on wording, so the helpers here are
 * severity-scoped and callers match on the substring they actually care about.
 *
 * The public surface is deliberately free of Kermit types: a consumer of `:test:world` should not need
 * the logging library on its compile classpath to read what the world logged.
 */
class WorldLog {

    /** One recorded line: the severity's name (`Error`, `Warn`, `Info`, …) and the rendered message. */
    class Line(val severity: String, val message: String) {
        override fun toString(): String = "$severity: $message"
    }

    private val recorded = mutableListOf<Line>()

    /** Every line, in order. */
    val lines: List<Line> get() = recorded.toList()

    fun at(severity: String): List<String> =
        recorded.filter { it.severity == severity }.map { it.message }

    /** The lines that reach crash reporting as **events** rather than breadcrumbs. */
    fun errors(): List<String> = at(Severity.Error.name)

    fun warnings(): List<String> = at(Severity.Warn.name)

    fun infos(): List<String> = at(Severity.Info.name)

    /**
     * A logger that records here **in addition to** wherever the process already logs — the desktop world
     * harness reads its engine console off the platform writer, so replacing it would blank the harness.
     */
    fun logger(tag: String): Logger {
        val writers = Logger.config.logWriterList + object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                recorded += Line(severity.name, message)
            }
        }
        return Logger(loggerConfigInit(*writers.toTypedArray()), tag)
    }
}
