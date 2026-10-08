package app.snapsync.http

/**
 * The top-level DER elements of [bytes], in order — how an Android key attestation's certificate chain, handed across
 * the port as concatenated DER, becomes the backend's list of certificates. Each element is self-delimiting (a tag, a
 * length, that many bytes), so nothing but DER's own framing is read. Throws on bytes that are not a sequence of
 * whole DER elements: a chain cut short must not reach the backend as fewer certificates.
 */
internal fun derElements(bytes: ByteArray): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    var i = 0
    while (i < bytes.size) {
        val start = i
        require(
            bytes[i++].toInt() and TAG_NUMBER_MASK != TAG_NUMBER_MASK,
        ) { "DER: a high tag number starts no certificate" }
        require(i < bytes.size) { "DER: truncated length at byte $i" }
        var length = bytes[i++].toInt() and BYTE
        if (length and LONG_FORM != 0) {
            val count = length and LONG_FORM.inv()
            require(count in 1..MAX_LENGTH_BYTES && i + count <= bytes.size) { "DER: bad length at byte ${i - 1}" }
            length = 0
            repeat(count) { length = (length shl BITS) or (bytes[i++].toInt() and BYTE) }
        }
        // At most [MAX_LENGTH_BYTES] length bytes: a length below 2^24, never negative.
        require(i + length <= bytes.size) { "DER: element at byte $start runs past the end" }
        i += length
        out += bytes.copyOfRange(start, i)
    }
    return out
}

private const val BYTE = 0xff
private const val BITS = 8
private const val LONG_FORM = 0x80
private const val TAG_NUMBER_MASK = 0x1f
private const val MAX_LENGTH_BYTES = 3
