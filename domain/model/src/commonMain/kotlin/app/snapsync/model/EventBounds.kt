package app.snapsync.model

/**
 * The reporting channel's ceiling on one event's **decoded** body (capability `crash-reporting`), measured against
 * the real Bugsink instance on 2026-07-29. The compressed wire size says nothing about it: a 1.5 MB event went over
 * the wire as 3.8 KB.
 *
 * ⚠️ Crossing it does not lose one event, it blocks the queue. sentry-cocoa deletes a cached envelope only on a
 * `200` and always sends the oldest first, so a refused one is re-sent on every trigger and holds back every later
 * report from its process, across launches, until 30 newer envelopes evict it (`SentryHttpTransport.m` at 8.58.2).
 * That is why every event is bounded **by construction**: each part carries a cap, and the caps sum below this.
 * The sum is on [DIAGNOSTIC_LOG_BUDGET_BYTES].
 */
const val MAX_EVENT_BYTES: Int = 1_048_576

/**
 * How many breadcrumbs an event carries. This is the SDK's default, set explicitly so the sum does not rest on a
 * default a later SDK may change.
 */
const val MAX_BREADCRUMBS: Int = 100

/**
 * UTF-8 bytes one breadcrumb's message and string data values may carry **together**, message first. A per-field
 * cap would leave the total open, because an SDK breadcrumb can carry several data keys.
 */
const val BREADCRUMB_TEXT_BYTES: Int = 512

/**
 * UTF-8 bytes an automatic event's message, and each of its exception values, may carry. This is not part of a
 * tight budget, because automatic events carry no log tails. It stops one runaway string (say, an exception
 * quoting a response body) from making an event unsendable.
 */
const val EVENT_TEXT_BYTES: Int = 8_192

/**
 * [text] cut to at most [maxBytes] UTF-8 bytes, marker included (capability `crash-reporting`).
 *
 * A text within the cap is returned unchanged. A cut text ends in `…[+<n> B]`, naming the bytes dropped, so a
 * reader never mistakes a cut line for a whole one. The cut never splits a UTF-8 sequence. The cap is counted in
 * bytes rather than chars, because the ceiling it protects is bytes and a char count under-counts non-ASCII text
 * by up to four times.
 */
fun capUtf8(text: String, maxBytes: Int): String {
    val bytes = text.encodeToByteArray()
    if (bytes.size <= maxBytes) return text
    // The dropped count is at most bytes.size, so a marker sized for it is the longest this cut can need.
    var keep = (maxBytes - truncationMarker(bytes.size).encodeToByteArray().size).coerceAtLeast(0)
    while (keep > 0 && bytes[keep].isContinuationByte()) keep--
    return bytes.decodeToString(0, keep) + truncationMarker(bytes.size - keep)
}

/**
 * [texts] sharing one cap of [maxBytes] UTF-8 bytes, in order: each text takes what it needs from what the ones
 * before it left, and the first text that does not fit is cut with [capUtf8]. A text that does not fit and finds
 * too little room left for a marker becomes empty. Whole texts are never touched, and the device log keeps every
 * dropped text in full.
 */
fun capAllUtf8(texts: List<String>, maxBytes: Int): List<String> {
    var remaining = maxBytes
    return texts.map { text ->
        val capped = when {
            text.encodeToByteArray().size <= remaining -> text
            remaining < MIN_CUT_BYTES -> ""
            else -> capUtf8(text, remaining)
        }
        remaining -= capped.encodeToByteArray().size
        capped
    }
}

private fun truncationMarker(droppedBytes: Int): String = "…[+$droppedBytes B]"

/**
 * Below this a cut would be all marker, and possibly longer than the room left, so a text that meets it is
 * emptied instead. A marker is at most 18 bytes (`…` is 3, and an Int count has at most 10 digits).
 */
private const val MIN_CUT_BYTES: Int = 24

private fun Byte.isContinuationByte(): Boolean = (toInt() and 0xC0) == 0x80
