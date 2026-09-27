package app.snapsync.rig

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit

/**
 * What the JVM host's app logged, in order — `/device/logs`' answer for the app process, as a device's `debug.log` is
 * the phone's. It ADDS a writer to wherever the process already logs, so nothing else loses a line.
 */
internal class RecordedLog {
    private val recorded = mutableListOf<String>()

    /** Every line, in order, as `<severity>: <message>`. */
    val lines: List<String> get() = synchronized(recorded) { recorded.toList() }

    /** A logger that records here as well as wherever the process logs. */
    fun logger(tag: String): Logger {
        val writers = Logger.config.logWriterList + object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                synchronized(recorded) { recorded += "${severity.name}: $message" }
            }
        }
        return Logger(loggerConfigInit(*writers.toTypedArray()), tag)
    }
}
