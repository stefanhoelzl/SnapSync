package app.snapsync.ios

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

/**
 * A Kermit writer that appends every log line to `Documents/debug.log` inside the app's own
 * container — a reliable device-log channel that sidesteps os_log's `<private>` redaction (the
 * `NSLog`-based [PublicNSLogWriter] is redacted on current iOS). Pull off-device with
 * `pymobiledevice3 apps pull app.snapsync Documents/debug.log`. Test-path only.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FileLogWriter : LogWriter() {

    private val path: String? = run {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
        docs?.let { "$it/debug.log" }
    }

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val p = path ?: return
        val line = buildString {
            append(NSDate().description ?: "")
            append(" [").append(severity.name).append('/').append(tag).append("] ").append(message)
            if (throwable != null) append(" | ").append(throwable.stackTraceToString())
            append('\n')
        }
        val f = fopen(p, "a") ?: return
        fputs(line, f)
        fclose(f)
    }
}
