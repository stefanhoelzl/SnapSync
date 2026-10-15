package app.snapsync.format

import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.time.Timestamp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The **one text form of a [Timestamp]** — the form the wire, the stores and the persisted config carry: UTC
 * `yyyy-MM-dd'T'HH:mm:ss'Z'`, second precision, no offset, no fractional second. A format is the boundary's, never the
 * domain's (`docs/patterns.md`, "The domain"): every reader and writer of a timestamp's text goes through here, so the
 * bytes cannot drift between them. `TimestampTextTest` pins them.
 *
 * A date compares as its instant, never as this text: an off-shape string (a fractional second, an offset) cannot sort
 * wrongly because no string is ever compared.
 */
object TimestampText {
    /** [timestamp]'s canonical text — for the wire, the stores and logs, never for a comparison. */
    fun format(timestamp: Timestamp): String {
        val dt = timestamp.at.toLocalDateTime(TimeZone.UTC)
        fun p(n: Int, width: Int) = n.toString().padStart(width, '0')
        return "${p(dt.year, 4)}-${p(dt.month.ordinal + 1, 2)}-${p(dt.day, 2)}" +
            "T${p(dt.hour, 2)}:${p(dt.minute, 2)}:${p(dt.second, 2)}Z"
    }

    /** The timestamp [text] names — any ISO-8601 instant, truncated to its second; a text that names none throws. */
    fun parse(text: String): Timestamp = Timestamp.of(Instant.parse(text))

    /** The timestamp [text] names, or `null` when it names none. */
    fun parseOrNull(text: String): Timestamp? = Instant.parseOrNull(text)?.let(Timestamp::of)

    /** The capture date a library's [text] records: [CaptureDate.Undated] when it is empty or names no instant. */
    fun captureDate(text: String): CaptureDate = parseOrNull(text)?.let(CaptureDate::Dated) ?: CaptureDate.Undated

    /** [date]'s text as a library record carries it — the inverse of [captureDate]: empty when it is undated. */
    fun captureText(date: CaptureDate): String = when (date) {
        CaptureDate.Undated -> ""
        is CaptureDate.Dated -> format(date.at)
    }
}
