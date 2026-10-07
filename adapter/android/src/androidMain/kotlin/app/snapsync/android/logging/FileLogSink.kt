package app.snapsync.android.logging

import android.content.Context
import app.snapsync.android.storage.AndroidFiles
import app.snapsync.model.APP_LOG_FILE_NAME
import app.snapsync.model.utcLogStamp
import app.snapsync.ports.LogSink
import co.touchlab.kermit.Severity
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.time.Clock
import java.nio.file.Files as Nio

/**
 * The device-log FILE [LogSink] (capability `privacy-security`): appends every line to [file], the process's own
 * verbatim log — the one a bug report carries. Logcat ([LogcatSink]) is read only over a debugging connection and
 * cannot be read back by the app, so without this file a dump's `app_log` is empty.
 *
 * The Android twin of the iOS writer, byte for byte in what it writes: each line stamped ([utcLogStamp]) and written
 * as ONE append, so threads never tear a line; rolled to a `.1` sibling (replacing any prior one) once the file has
 * reached [maxBytes]. The channel is held open and the size tracked in memory from one read at open, as on iOS. A file
 * removed from outside is noticed by a re-check at most once per [RECHECK_INTERVAL_MS] and recreated; a failed write
 * drops the channel and retries the line once on a fresh one — nothing was written, so the retry cannot duplicate it.
 *
 * A sink never throws (the port's rule): this IS the log, so every I/O failure is dropped here, and a roll that fails
 * leaves the log growing past its cap rather than losing lines.
 */
class FileLogSink internal constructor(
    private val file: File,
    private val maxBytes: Long,
    /** The wall clock — replaced only by tests, to reach the periodic re-check. */
    private val clock: Clock,
) : LogSink {

    constructor(file: File, maxBytes: Long = DEFAULT_MAX_BYTES) : this(file, maxBytes, Clock.System)

    private val lock = Any()

    // All guarded by [lock].
    private var channel: FileChannel? = null
    private var size: Long = 0
    private var lastCheckMs: Long = 0

    override fun write(severity: Severity, tag: String, line: String) {
        val now = clock.now().toEpochMilliseconds()
        val bytes = "${utcLogStamp(now)} $line\n".encodeToByteArray()
        synchronized(lock) { appendLocked(bytes, now) }
    }

    private fun appendLocked(bytes: ByteArray, now: Long) {
        if (channel != null && now - lastCheckMs >= RECHECK_INTERVAL_MS) {
            lastCheckMs = now
            if (!file.exists()) closeLocked()
        }
        if (channel == null) {
            if (!openLocked()) return
            lastCheckMs = now
        }
        if (size >= maxBytes) roll()
        if (channel == null || writeAll(bytes)) return
        closeLocked()
        if (openLocked()) writeAll(bytes)
    }

    private fun writeAll(bytes: ByteArray): Boolean = try {
        val buffer = ByteBuffer.wrap(bytes)
        // An APPEND channel writes a file-sized buffer in one `write(2)`; the loop only finishes a short write.
        while (buffer.hasRemaining()) size += checkNotNull(channel).write(buffer)
        true
    } catch (_: IOException) {
        false
    }

    private fun openLocked(): Boolean = try {
        file.parentFile?.let { Nio.createDirectories(it.toPath()) }
        val opened = FileChannel.open(
            file.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )
        channel = opened
        size = opened.size()
        true
    } catch (_: IOException) {
        false
    }

    private fun closeLocked() {
        try {
            channel?.close()
        } catch (_: IOException) {
            // Closing a channel that failed: nothing left to lose.
        }
        channel = null
    }

    /** Roll the log to its `.1` sibling (replacing any prior one) and continue in a fresh file. */
    private fun roll() {
        closeLocked()
        try {
            Nio.move(file.toPath(), File(file.path + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            // Dropped: the reopen finds the same file, still over the cap, and the next line tries again.
        }
        openLocked()
    }

    companion object {
        /** Past this the log rolls — the iOS writer's ceiling. */
        const val DEFAULT_MAX_BYTES = 10L * 1024 * 1024

        /** How often, at most, the file's existence is re-checked to notice a log removed from outside. */
        internal const val RECHECK_INTERVAL_MS = 1_000L

        /** The app's log: [APP_LOG_FILE_NAME] in the private area, where the dump's log-tail read looks for it. */
        fun forApp(context: Context): FileLogSink = FileLogSink(
            File(AndroidFiles.privateArea(context), APP_LOG_FILE_NAME),
        )
    }
}
