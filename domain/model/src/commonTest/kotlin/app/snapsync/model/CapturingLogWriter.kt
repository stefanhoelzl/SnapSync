package app.snapsync.model

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit

/**
 * A Kermit writer that keeps every line it is handed, with its severity — what a test asserts a unit's logging by.
 * One per test module: a test source set reaches no other module's.
 */
class CapturingLogWriter : LogWriter() {
    val lines: MutableList<Pair<Severity, String>> = mutableListOf()

    val severities: List<Severity> get() = lines.map { it.first }

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        lines += severity to message
    }

    /** A logger writing only here, at every severity. */
    fun logger(tag: String = "test"): Logger = Logger(loggerConfigInit(this), tag)
}
