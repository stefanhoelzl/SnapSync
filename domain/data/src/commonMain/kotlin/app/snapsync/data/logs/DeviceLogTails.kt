package app.snapsync.data.logs

import app.snapsync.domain.report.Fact
import app.snapsync.format.DeviceLogText
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.FileTail
import app.snapsync.ports.Files

/**
 * Which process's device log to read — both live on this device, only one is this process's own: the app's in its
 * private area, the extension's in the shared one (the only placement the app, which assembles a bug report, can read).
 */
enum class LoggingProcess(internal val area: FileArea, internal val file: String, internal val named: String) {
    APP(FileArea.PRIVATE, DeviceLogText.APP_LOG, "app"),
    EXTENSION(FileArea.SHARED, DeviceLogText.EXTENSION_LOG, "extension"),
}

/**
 * The read side of the two device logs: the tail of a process's log, bounded in bytes.
 *
 * It **seeks** ([Files.readTail]): a log is bounded at 10 MB and a report wants its last few hundred KB. A tail that
 * began mid-file is cut to its first whole line; a log that fit the budget was read from its first byte and is kept
 * whole. A log that is not there, or holds nothing, is [Fact.Unsupported] — never an empty text, which would read as a
 * log that ran and said nothing; one that could not be read is [Fact.Failed], with why. The rolled `.1` sibling is
 * stale and never read.
 */
class DeviceLogTails(private val files: Files) {

    /** The last [maxBytes]-ish bytes of [process]'s log, line-aligned. */
    fun tail(process: LoggingProcess, maxBytes: Int): Fact<String> =
        when (val read = files.readTail(process.area, process.file, maxBytes)) {
            is FileResult.Ok -> text(read.value)
            FileResult.NotFound -> Fact.Unsupported
            FileResult.AreaUnavailable -> Fact.Failed("the ${process.named} log's area is unavailable")
            is FileResult.Denied -> Fact.Failed("the ${process.named} log was denied: ${read.detail}")
            is FileResult.Failed -> Fact.Failed("the ${process.named} log could not be read: ${read.detail}")
        }

    /** The tail as text: none when it holds nothing. */
    private fun text(tail: FileTail): Fact<String> = when (tail.bytes.isEmpty()) {
        true -> Fact.Unsupported
        false -> Fact.Known(aligned(tail.bytes.decodeToString(), tail.cut))
    }

    /**
     * A cut tail from its first whole line — the log is UTF-8, and a tail may start mid-codepoint, which decodes to a
     * replacement character and goes with that partial line. A cut tail with no newline at all is one very long line,
     * kept whole: dropping it would return nothing.
     */
    private fun aligned(text: String, cut: Boolean): String = when (cut) {
        true -> text.substringAfter('\n', missingDelimiterValue = text)
        false -> text
    }
}
