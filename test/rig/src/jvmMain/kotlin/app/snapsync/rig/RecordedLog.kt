package app.snapsync.rig

import app.snapsync.ports.LogSink
import co.touchlab.kermit.Severity

/**
 * What the JVM host's app logged, in order — `/device/logs`' answer for the app process, as a device's `debug.log` is
 * the phone's. The app process's one [LogSink]: every line the process logs is written here, formatted as a device's
 * file sink receives it, and echoed to standard output so a test run still shows it.
 */
internal class RecordedLog : LogSink {
    private val recorded = mutableListOf<String>()

    /** Every line, in order, as the process's log writer formatted it. */
    val lines: List<String> get() = synchronized(recorded) { recorded.toList() }

    override fun write(severity: Severity, tag: String, line: String) {
        synchronized(recorded) { recorded += line }
        println(line)
    }
}
