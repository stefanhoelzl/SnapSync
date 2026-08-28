package app.snapsync.model

private const val UPPER_HEX = "0123456789ABCDEF"

private fun Int.isAlphaNumeric(): Boolean =
    this in 'A'.code..'Z'.code || this in 'a'.code..'z'.code || this in '0'.code..'9'.code

/**
 * Percent-encode [filename] for the edge URL's `file/<…>` path segment: escape every byte outside
 * `[A-Za-z0-9._-]` as uppercase `%XX` (multi-byte UTF-8 escaped byte-by-byte). The mapping is
 * deterministic and injective — distinct filenames never collide — which is where upload idempotency
 * lives. Any `/` becomes `%2F`, so the edge endpoint decodes the segment back to one slash-free
 * filename (it rejects a decoded `/`). The encoded string is what the device sends; the endpoint
 * percent-decodes it (Hono route param) before validating and re-encoding for the storage key.
 */
internal fun encodeFilenameSegment(filename: String): String {
    val bytes = filename.encodeToByteArray()
    val out = StringBuilder(bytes.size)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        if (v.isAlphaNumeric() || v == '.'.code || v == '_'.code || v == '-'.code) {
            out.append(v.toChar())
        } else {
            out.append('%').append(UPPER_HEX[v ushr 4]).append(UPPER_HEX[v and 0x0F])
        }
    }
    return out.toString()
}

/**
 * The path of an absolute `http(s)` URL — everything from the `/` that ends the authority up to a `?` or
 * `#`, or `"/"` when there is none.
 *
 * Pure string work, so the ledger can record where an upload was addressed without a platform URL type
 * (capability `sync-ledger`). The PATH rather than the whole URL: it is what the platform must preserve
 * to perform the request at all, and it is unaffected by any handling of the query.
 *
 * Percent-encoding is left exactly as composed. That is safe for what this addresses because a
 * normalized `assetId` and a role token contain only unreserved characters, so the encoded and decoded
 * spellings coincide — and the tier that compares it keeps a fallback for any row where they would not.
 */
fun destinationPathOf(url: String): String {
    val afterScheme = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val pathStart = url.indexOf('/', afterScheme)
    if (pathStart < 0) return "/"
    val end = url.indexOfFirst(pathStart) { it == '?' || it == '#' }
    return url.substring(pathStart, end)
}

private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    for (i in from until length) if (predicate(this[i])) return i
    return length
}
