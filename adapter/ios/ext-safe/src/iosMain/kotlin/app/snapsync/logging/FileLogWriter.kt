package app.snapsync.logging

import app.snapsync.model.utcLogStamp
import app.snapsync.objc.checkedObjC
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.posix.O_APPEND
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.fstat
import platform.posix.open
import platform.posix.stat
import platform.posix.write

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
 *
 * **One descriptor, held open.** Measured on an SE2, the per-line cost of this writer was ~0.48 ms in the
 * foreground and ~5.0 ms in a background (darwinbg) wake, and a wake logs 27 (median) to 1,620 lines: every
 * line paid a `stat` (the roll check), a `fileExists`, an `open`, the `write` and a `close`, plus an `NSDate`
 * and its `description` for the stamp. Now the descriptor is opened once (and after each roll), the file's
 * size is tracked in memory from ONE `fstat` at open, and the stamp is arithmetic ([utcLogStamp]) — the
 * bytes written are identical, and so is the roll: the size is checked against [maxBytes] before each
 * line, exactly as the per-line `stat` did.
 *
 * **A vanished file is reopened by a periodic check, not per line.** Nothing in production removes a live
 * log (the extension's launch-time cleanup removes only a path the writer is NOT using), but a file removed
 * or replaced from outside would otherwise swallow every later line into an unlinked inode. So at most once
 * per [RECHECK_INTERVAL_MS] the path is `stat`ed and compared with the open descriptor's identity; a missing
 * or different file is reopened (and created). A failed `write` also closes the descriptor and retries the
 * line once on a fresh one — nothing was written, so the retry cannot duplicate it.
 *
 * Thread safety: Kermit calls this from any thread. The descriptor, the byte count and the roll are guarded
 * by one lock, so a roll can never interleave with another thread's write; the line itself is still one
 * `write()`, so the atomic-append guarantee does not depend on the lock.
 */
@OptIn(ExperimentalForeignApi::class)
class FileLogWriter internal constructor(
    private val path: String?,
    private val maxBytes: Long,
    /** The wall clock, in epoch milliseconds — injected only by tests, to reach the periodic re-check. */
    private val nowMillis: () -> Long,
) : LogWriter() {

    constructor(path: String?, maxBytes: Long = 10L * 1024 * 1024) :
        this(path, maxBytes, { Clock.System.now().toEpochMilliseconds() })

    private val lock = NSLock()

    // All guarded by [lock].
    private var fd: Int = CLOSED
    private var size: Long = 0
    private var device: Int = 0
    private var inode: ULong = 0u
    private var lastCheckMs: Long = 0

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val p = path ?: return
        val now = nowMillis()
        val ctx = LogContext.current
        val line = buildString {
            append(utcLogStamp(now))
            append(' ')
            if (ctx != null) append('[').append(ctx).append("] ")
            append('[').append(severity.name).append('/').append(tag).append("] ").append(message)
            if (throwable != null) append(" | ").append(throwable.stackTraceToString())
            append('\n')
        }
        val bytes = line.encodeToByteArray()
        lock.lock()
        try {
            appendLocked(p, bytes, now)
        } finally {
            lock.unlock()
        }
    }

    /** Roll if due, then one `O_APPEND` `write()` of the whole line (never torn across threads). */
    private fun appendLocked(path: String, bytes: ByteArray, now: Long) {
        if (fd != CLOSED && now - lastCheckMs >= RECHECK_INTERVAL_MS) {
            lastCheckMs = now
            if (!stillOurs(path)) closeLocked()
        }
        if (fd == CLOSED) {
            if (!openLocked(path)) return
            lastCheckMs = now
        }
        if (size >= maxBytes) roll(path)
        if (fd == CLOSED || writeAll(bytes)) return
        // A failed write wrote nothing: drop this descriptor and give the line one fresh one.
        closeLocked()
        if (openLocked(path)) writeAll(bytes)
    }

    private fun writeAll(bytes: ByteArray): Boolean {
        val written = bytes.usePinned { pinned -> write(fd, pinned.addressOf(0), bytes.size.convert()) }
        if (written > 0) size += written
        return written >= 0
    }

    /**
     * Open [path] for appending, creating it first if absent, and read its size and identity once. The
     * creation goes through the same `NSFileManager.createFileAtPath` the per-line path used, so a fresh
     * file carries the same attributes (and data-protection class) as before.
     */
    private fun openLocked(path: String): Boolean {
        val mgr = NSFileManager.defaultManager
        if (!mgr.fileExistsAtPath(path)) mgr.createFileAtPath(path, contents = null, attributes = null)
        val opened = open(path, O_WRONLY or O_APPEND)
        if (opened < 0) return false
        fd = opened
        memScoped {
            val st = alloc<stat>()
            if (fstat(opened, st.ptr) == 0) {
                size = st.st_size
                device = st.st_dev
                inode = st.st_ino
            } else {
                size = 0
            }
        }
        return true
    }

    /** Whether [path] still names the file the open descriptor writes to (not removed, not replaced). */
    private fun stillOurs(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        stat(path, st.ptr) == 0 && st.st_dev == device && st.st_ino == inode
    }

    private fun closeLocked() {
        if (fd != CLOSED) close(fd)
        fd = CLOSED
    }

    /**
     * Roll the log to its `.1` sibling (replacing any prior one) and continue in a fresh file.
     *
     * Every failure below is dropped, deliberately and visibly: this IS the log, so there is nowhere to report
     * one, and a roll that fails leaves the log growing past its cap rather than losing lines — the reopen then
     * finds the same file, its size still over the cap, and the next line tries again, as the per-line check did.
     */
    private fun roll(path: String) {
        closeLocked()
        val mgr = NSFileManager.defaultManager
        val rolled = "$path.1"
        checkedObjC("removeItemAtPath") { mgr.removeItemAtPath(rolled, error = it) } // absent on the first roll
        checkedObjC("moveItemAtPath") { mgr.moveItemAtPath(path, toPath = rolled, error = it) }
        openLocked(path)
    }

    private companion object {
        const val CLOSED = -1

        /** How often, at most, the path is re-`stat`ed to notice a log removed or replaced from outside. */
        const val RECHECK_INTERVAL_MS = 1_000L
    }
}
