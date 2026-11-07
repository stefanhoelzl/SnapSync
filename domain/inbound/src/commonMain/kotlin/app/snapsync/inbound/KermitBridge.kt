package app.snapsync.inbound

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.LogWriter as KermitWriter

/**
 * The one Kermit writer a process installs: a line written through Kermit — by an adapter, a root or an old-zone helper
 * not yet on [Log] — becomes a [LogLine], its free text the event and no fields, and goes to [Log]'s writers like every
 * other line. It carries no trigger of its own: a writer reads the label the calling thread carries.
 *
 * The only Kermit code the core's composition builds. Deleted with the last Kermit caller (phase 5).
 */
class KermitBridge : KermitWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) =
        Log.write(LogLine(severity.level(), tag, null, message, emptyList(), throwable))

    /** Kermit's six levels are [LogLevel]'s six, named alike (`KermitBridgeTest` pins each). */
    private fun Severity.level(): LogLevel = LogLevel.valueOf(name.uppercase())
}

/**
 * A process's logging globals — [Log]'s writers, and Kermit's, whose one writer then is the [KermitBridge] onto them —
 * and whether the process owns them (`docs/architecture.md`, "One shared composition"): **a process that supplies log
 * sinks owns them**. One that supplies none ([sinks] `0`) leaves both alone: a JVM hosts many "processes" in one VM,
 * where they are VM-global, and a composition there must not take them over.
 */
class ProcessLogging(private val writers: List<LogWriter>, private val sinks: Int) {
    /** Install [writers] — where this process owns the globals, and nowhere else. */
    @CompositionOnly
    fun install() {
        if (sinks == 0) return
        Log.install(writers)
        Logger.setLogWriters(KermitBridge())
    }
}
