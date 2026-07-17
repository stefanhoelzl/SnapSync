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
