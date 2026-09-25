package app.snapsync.services.logs

import app.snapsync.model.APP_LOG_FILE_NAME
import app.snapsync.model.EXTENSION_LOG_FILE_NAME
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.ports.DeviceLogSource
import app.snapsync.ports.Files

/**
 * The read side of the two device logs (capability `privacy-security`): [DeviceLogSource] over [Files] — the
 * app's own log in its private area, the extension's in the shared one (the only placement the app, which
 * assembles a diagnostic dump, can read).
 *
 * It **seeks** ([Files.readTail]): a log is bounded at 10 MB and a dump wants its last few hundred KB. A tail that
 * began mid-file is cut to its first whole line; a log that fit the budget was read from its first byte and is
 * kept whole. `null` — never an empty string, never a partial lie — when there is nothing to read, including a
 * log that could not be read. The rolled `.1` sibling is stale and never read.
 */
class LogTailService(private val files: Files) : DeviceLogSource {

    override suspend fun tail(process: DeviceLogSource.Process, maxBytes: Int): String? {
        if (maxBytes <= 0) return null
        val (area, name) = when (process) {
            DeviceLogSource.Process.APP -> FileArea.PRIVATE to APP_LOG_FILE_NAME
            DeviceLogSource.Process.EXTENSION -> FileArea.SHARED to EXTENSION_LOG_FILE_NAME
        }
        val tail = (files.readTail(area, name, maxBytes) as? FileResult.Ok)?.value ?: return null
        if (tail.bytes.isEmpty()) return null
        // The log is UTF-8 text; a tail may start mid-codepoint, which decodes to a replacement char and is
        // discarded with the partial first line just below.
        val text = tail.bytes.decodeToString()
        return if (tail.cut) fromFirstWholeLine(text) else text
    }
}

/**
 * Drop everything before the first newline, so a tail never begins mid-line. Text with no newline at all is
 * returned whole — one very long line, or a log shorter than the budget, and dropping it would return nothing.
 */
internal fun fromFirstWholeLine(text: String): String {
    val firstBreak = text.indexOf('\n')
    if (firstBreak < 0) return text
    return text.substring(firstBreak + 1)
}
