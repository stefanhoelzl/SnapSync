package app.snapsync.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.posix.O_APPEND
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.write
import kotlin.time.Clock

/**
 * A Kermit writer that appends every log line to the file at [path] — the reliable, verbatim
 * device-log channel that sidesteps os_log's `<private>` redaction entirely (the `NSLog`-based
 * [PublicNSLogWriter] is redacted on current iOS). Test-path only.
 *
 * Consolidated here (capability `diagnostic-logging`, D1): the app and the upload extension are
 * separate processes, and one writer serves both. It takes its *destination* rather than resolving
 * one, because the two processes no longer write to the same place — the app to its own
 * `Documents/debug.log`, the extension to `ext-debug.log` in the shared App Group so the app can read
 * it for a diagnostic dump (see `LogDestinations.kt`). The writer needs no *process identity*, which
 * is what D1's "parameter-free" was about; it needs a path, and the composition roots choose it.
 *
 * Each line carries the ambient `[LogContext.current]` prefix, and is written as a single atomic
 * `O_APPEND` `write()` (D2 read-side, D7) so concurrent-thread writes never tear a line. The file is
 * bounded by rolling to a `.1` sibling past [maxBytes] (D7).
 */
@OptIn(ExperimentalForeignApi::class)
class FileLogWriter(
    private val path: String?,
    private val maxBytes: Long = 10L * 1024 * 1024,
) : LogWriter() {

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val p = path ?: return
        rollIfNeeded(p)
        val ctx = LogContext.current
        val line = buildString {
            append(stamp())
            append(' ')
            if (ctx != null) append('[').append(ctx).append("] ")
            append('[').append(severity.name).append('/').append(tag).append("] ").append(message)
            if (throwable != null) append(" | ").append(throwable.stackTraceToString())
            append('\n')
        }
        appendAtomically(p, line)
    }

    /**
     * The line's timestamp, at **millisecond** resolution (capability `diagnostic-logging`).
     *
     * `NSDate.description` is a fixed `yyyy-MM-dd HH:mm:ss +0000` in UTC — seconds only, and at that
     * resolution the order of two lines written in the same second is unrecoverable. That ordering is
     * exactly what separates "the platform call was slow" from "the process was frozen after it
     * returned": diagnosing SNAPSYNC-6 required deducing it from durations because the stamps could not
     * say. No `NSDateFormatter` — it would allocate one per line on a path that runs for every log line.
     *
     * ONE clock read, split into whole seconds and milliseconds. The `NSDate` is built from the floored
     * seconds so its description sits exactly on a second boundary and cannot round away from the
     * milliseconds printed beside it; reading the clock twice could straddle a boundary and stamp a line
     * a full second wrong, which is the one error this change exists to remove.
     */
    private fun stamp(): String {
        val epochMillis = Clock.System.now().toEpochMilliseconds()
        val millis = (epochMillis % 1000).toInt()
        val desc = NSDate.dateWithTimeIntervalSince1970((epochMillis / 1000).toDouble()).description
            ?: return ""
        val zone = desc.lastIndexOf(' ')
        if (zone <= 0) return desc // not the documented shape — stamp it verbatim rather than guess
        return buildString {
            append(desc, 0, zone)
            append('.')
            if (millis < 100) append('0')
            if (millis < 10) append('0')
            append(millis)
            append(desc, zone, desc.length)
        }
    }

    /** Roll the log to its `.1` sibling (replacing any prior one) once it exceeds [maxBytes]. */
    private fun rollIfNeeded(path: String) {
        val mgr = NSFileManager.defaultManager
        val attrs = mgr.attributesOfItemAtPath(path, error = null) ?: return
        val size = (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: return
        if (size < maxBytes) return
        val rolled = "$path.1"
        mgr.removeItemAtPath(rolled, error = null) // ignore: absent on the first roll
        mgr.moveItemAtPath(path, toPath = rolled, error = null)
    }

    /** One `O_APPEND` `write()` per line so appends stay atomic (never torn across threads). */
    private fun appendAtomically(path: String, line: String) {
        val bytes = line.encodeToByteArray()
        if (bytes.isEmpty()) return
        val mgr = NSFileManager.defaultManager
        if (!mgr.fileExistsAtPath(path)) {
            mgr.createFileAtPath(path, contents = null, attributes = null)
        }
        val fd = open(path, O_WRONLY or O_APPEND)
        if (fd < 0) return
        bytes.usePinned { pinned ->
            write(fd, pinned.addressOf(0), bytes.size.convert())
        }
        close(fd)
    }
}
