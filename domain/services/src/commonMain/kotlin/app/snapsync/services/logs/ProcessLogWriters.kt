package app.snapsync.services.logs

import app.snapsync.ports.LogSink
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.StaticConfig

/**
 * One process's Kermit [writers], and whether it owns the VM's global writer list (`docs/architecture.md`, "One shared
 * composition"): **a process that supplies log [sinks] owns it**, and [install] puts its writers there. A process that
 * supplies none leaves it alone: a JVM hosts many "processes" in one VM, where that list is VM-global, and a composition
 * there must not take it over.
 */
class ProcessLogWriters(val writers: List<LogWriter>, sinks: List<LogSink>) {

    /** Whether this process installs [writers] as Kermit's global list. */
    val ownsGlobalLogger: Boolean = sinks.isNotEmpty()

    /** Install [writers] as Kermit's global list — where this process owns it, and nowhere else. */
    fun install() {
        if (ownsGlobalLogger) Logger.setLogWriters(writers)
    }

    /**
     * A logger over THIS process's writers. Where the process owns the global list that is the same list; where it does
     * not (a JVM "process"), the lines reach the process's own sinks AND whatever the VM logs to, so a composition's
     * line is never lost to a writer list another composition replaced.
     */
    fun logger(tag: String): Logger {
        val all = if (ownsGlobalLogger) writers else writers + Logger.config.logWriterList
        return Logger(StaticConfig(logWriterList = all), tag)
    }
}
