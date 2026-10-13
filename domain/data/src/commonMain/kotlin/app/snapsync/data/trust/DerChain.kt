package app.snapsync.data.trust

/**
 * The top-level DER elements of a byte string, in order — how an Android key attestation's certificate chain, handed
 * across the integrity port as concatenated DER, becomes the backend's list of certificates. Each element is
 * self-delimiting (a tag, a length, that many bytes), so nothing but DER's own framing is read.
 */
internal object DerChain {
    /** The elements of [bytes], or `null` when they are not a sequence of whole DER elements. */
    fun elements(bytes: ByteArray): List<ByteArray>? {
        val out = mutableListOf<ByteArray>()
        var at = 0
        while (at < bytes.size) {
            val end = elementEnd(bytes, at) ?: return null
            out += bytes.copyOfRange(at, end)
            at = end
        }
        return out
    }

    /** Where the element starting at [start] ends, or `null` for one that is malformed or runs past the end. */
    private fun elementEnd(bytes: ByteArray, start: Int): Int? {
        val lengthAt = start + 1
        val tagNumber = bytes[start].toInt() and TAG_NUMBER_MASK
        val first = bytes.getOrNull(lengthAt)?.toInt()?.and(BYTE)
        return first?.takeIf { tagNumber != TAG_NUMBER_MASK }?.let { length(bytes, lengthAt, it) }
            ?.takeIf { it <= bytes.size }
    }

    /** The end of the contents whose length begins at [lengthAt] with byte [first], short or long form. */
    private fun length(bytes: ByteArray, lengthAt: Int, first: Int): Int? = when (first and LONG_FORM == 0) {
        true -> lengthAt + 1 + first
        false -> longLength(bytes, lengthAt + 1, first and LONG_FORM.inv())
    }

    /** A long-form length of [count] bytes at [at]: at most [MAX_LENGTH_BYTES], so never negative. */
    private fun longLength(bytes: ByteArray, at: Int, count: Int): Int? =
        (at + count).takeIf { count in 1..MAX_LENGTH_BYTES && it <= bytes.size }?.let { contentsAt ->
            contentsAt + (at until contentsAt).fold(0) { length, i -> (length shl BITS) or (bytes[i].toInt() and BYTE) }
        }

    private const val BYTE = 0xff
    private const val BITS = 8
    private const val LONG_FORM = 0x80
    private const val TAG_NUMBER_MASK = 0x1f
    private const val MAX_LENGTH_BYTES = 3
}
