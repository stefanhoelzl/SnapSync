package app.snapsync.s3

private const val LOWER_HEX = "0123456789abcdef"
private const val UPPER_HEX = "0123456789ABCDEF"

/**
 * Lowercase hex of [bytes]. SigV4 renders both the canonical-request hash and the final signature
 * as lowercase hex. Hand-rolled (no encoding dependency), per the locked design.
 */
internal fun hex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        out.append(LOWER_HEX[v ushr 4])
        out.append(LOWER_HEX[v and 0x0F])
    }
    return out.toString()
}

/**
 * Percent-encode [input]'s UTF-8 bytes, passing through bytes for which [unreserved] is true and
 * emitting every other byte as uppercase `%XX`.
 */
private inline fun percentEncode(input: String, unreserved: (Int) -> Boolean): String {
    val bytes = input.encodeToByteArray()
    val out = StringBuilder(bytes.size)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        if (unreserved(v)) {
            out.append(v.toChar())
        } else {
            out.append('%').append(UPPER_HEX[v ushr 4]).append(UPPER_HEX[v and 0x0F])
        }
    }
    return out.toString()
}

private fun Int.isAlphaNumeric(): Boolean =
    this in 'A'.code..'Z'.code || this in 'a'.code..'z'.code || this in '0'.code..'9'.code

/**
 * Object-key encoding (docs/design.md §3.1): escape every byte outside `[A-Za-z0-9._-]`. Stricter
 * than RFC 3986 (it also escapes `~`), which is safe — the same single-encoded string is reused
 * verbatim as the SigV4 canonical URI, so wire path and signed path stay byte-identical.
 */
internal fun encodeObjectKeySegment(filename: String): String =
    percentEncode(filename) { it.isAlphaNumeric() || it == '.'.code || it == '_'.code || it == '-'.code }

/**
 * RFC 3986 encoding for SigV4 canonical query keys and values (unreserved = `A-Za-z0-9-._~`). This
 * is what turns `/` in the credential into `%2F` and `;` in the signed-headers list into `%3B`.
 */
internal fun encodeRfc3986(value: String): String =
    percentEncode(value) {
        it.isAlphaNumeric() || it == '-'.code || it == '.'.code || it == '_'.code || it == '~'.code
    }
