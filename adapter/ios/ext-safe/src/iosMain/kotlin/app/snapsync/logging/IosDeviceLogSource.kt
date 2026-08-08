package app.snapsync.logging

import app.snapsync.ports.DeviceLogSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.lseek
import platform.posix.open
import platform.posix.read

/**
 * The iOS seat of [DeviceLogSource] — the read side of the two device logs [FileLogWriter] writes.
 *
 * It **seeks**: a log is bounded at 10 MB and a dump wants its last few hundred KB, so it opens the
 * file, jumps to `size - maxBytes`, and reads forward. Reading the whole file to slice the end would
 * allocate 10 MB on a user-triggered path to keep 3% of it.
 *
 * Constructed in the **app** process (the only one that assembles a dump), where
 * `extensionLogDestination()` resolves the shared App-Group file the extension writes and
 * `appLogDestination()` resolves this process's own `Documents/debug.log`.
 *
 * **It hops nowhere, and that is deliberate.** The `open`/`lseek`/`read` sequence below blocks, but
 * where blocking work runs is the composition's decision rather than this seam's (spec
 * `module-architecture`, law "Dispatcher lanes are fixed by the composition"): the app's core scope is
 * a dedicated non-UI lane, so this is off main whether it hops or not. The only thing a hop could
 * still buy on that **serial** lane is throughput, and there is none here to buy. `CollectDiagnosticDump`
 * reads the two tails one after the other and data-dependently — the app's share of the budget is
 * whatever the extension's tail left — inside a single `tap.sendDiagnostics` command the operator's
 * sheet is waiting on, and each read is a few hundred KB from a local file. Nothing runs alongside
 * them that releasing the lane would let proceed.
 *
 * An earlier revision hopped to `Dispatchers.Default` and cited `module-architecture` for it, under a
 * rule ("sync-I/O port impls own their dispatcher hop") that same spec had already withdrawn — so a
 * reader who followed the citation arrived at a document contradicting the comment.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDeviceLogSource(
    private val appLogPath: String? = appLogDestination().path,
    private val extensionLogPath: String? = extensionLogDestination().path,
) : DeviceLogSource {

    override suspend fun tail(process: DeviceLogSource.Process, maxBytes: Int): String? {
        val path = when (process) {
            DeviceLogSource.Process.APP -> appLogPath
            DeviceLogSource.Process.EXTENSION -> extensionLogPath
        } ?: return null
        return readTail(path, maxBytes)?.let(::fromFirstWholeLine)
    }

    /** Read at most [maxBytes] from the end of [path]; `null` if it cannot be opened or read. */
    private fun readTail(path: String, maxBytes: Int): String? {
        if (maxBytes <= 0) return null
        val fd = open(path, O_RDONLY)
        if (fd < 0) return null
        try {
            val size = lseek(fd, 0, SEEK_END)
            if (size <= 0L) return null
            val take = minOf(size, maxBytes.toLong())
            if (lseek(fd, size - take, SEEK_SET) < 0) return null
            val buffer = ByteArray(take.toInt())
            var filled = 0
            buffer.usePinned { pinned ->
                while (filled < buffer.size) {
                    val n = read(fd, pinned.addressOf(filled), (buffer.size - filled).convert()).toInt()
                    if (n <= 0) break
                    filled += n
                }
            }
            if (filled == 0) return null
            // The log is written as UTF-8 text; a tail may start mid-codepoint, which decodes to a
            // replacement char and is discarded with the partial first line just below.
            return buffer.decodeToString(0, filled, throwOnInvalidSequence = false)
        } finally {
            close(fd)
        }
    }
}

/**
 * Drop everything before the first newline, so a tail never begins mid-line.
 *
 * A dump whose first line starts in the middle of a timestamp or a URL reads as corruption, and the
 * partial line carries no information anyway. Text with no newline at all is returned whole — it is
 * either one very long line or a log shorter than the budget, and in both cases dropping it would
 * mean returning nothing.
 */
internal fun fromFirstWholeLine(text: String): String {
    val firstBreak = text.indexOf('\n')
    if (firstBreak < 0) return text
    return text.substring(firstBreak + 1)
}
