package app.snapsync.contracts.attest

/**
 * The few DER encodings an X.509 certificate needs (ITU-T X.690) — enough to build the software attestation chain
 * [SoftwareKeyAttestation] presents, on every platform a test host runs on. Each function answers one complete
 * element: tag, length, contents.
 */
internal object Der {
    fun sequence(vararg elements: ByteArray): ByteArray = element(SEQUENCE, concat(elements))

    fun set(vararg elements: ByteArray): ByteArray = element(SET, concat(elements))

    fun integer(value: Long): ByteArray = element(INTEGER, twosComplement(value))

    fun enumerated(value: Long): ByteArray = element(ENUMERATED, twosComplement(value))

    fun octetString(bytes: ByteArray): ByteArray = element(OCTET_STRING, bytes)

    /** A BIT STRING of whole bytes: no unused bits. */
    fun bitString(bytes: ByteArray): ByteArray = element(BIT_STRING, byteArrayOf(0) + bytes)

    fun utf8String(text: String): ByteArray = element(UTF8_STRING, text.encodeToByteArray())

    /** `YYMMDDHHMMSSZ`, from its digits. */
    fun utcTime(digits: String): ByteArray = element(UTC_TIME, "${digits}Z".encodeToByteArray())

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val body = mutableListOf<Byte>()
        body += (arcs[0] * FIRST_ARC_FACTOR + arcs[1]).toByte()
        arcs.drop(2).forEach { body += base128(it).toList() }
        return element(OID, body.toByteArray())
    }

    /** `[number] EXPLICIT`, context-specific and constructed — tag numbers past 30 in the high-tag form. */
    fun explicit(number: Int, inner: ByteArray): ByteArray {
        val tag = if (number < HIGH_TAG) {
            byteArrayOf((CONTEXT_CONSTRUCTED or number).toByte())
        } else {
            byteArrayOf((CONTEXT_CONSTRUCTED or HIGH_TAG).toByte()) + base128(number.toLong())
        }
        return tag + length(inner.size) + inner
    }

    private fun element(tag: Int, contents: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(contents.size) + contents

    private fun length(size: Int): ByteArray = when {
        size < SHORT_LENGTH -> byteArrayOf(size.toByte())
        size <= BYTE_MASK -> byteArrayOf((LONG_LENGTH or 1).toByte(), size.toByte())
        else -> byteArrayOf((LONG_LENGTH or 2).toByte(), (size shr BITS).toByte(), size.toByte())
    }

    /** The minimal two's-complement big-endian encoding of [value]. */
    private fun twosComplement(value: Long): ByteArray {
        var bytes = ByteArray(Long.SIZE_BYTES) { (value shr (BITS * (Long.SIZE_BYTES - 1 - it))).toByte() }
        while (bytes.size > 1 &&
            ((bytes[0] == 0.toByte() && bytes[1] >= 0) || (bytes[0] == (-1).toByte() && bytes[1] < 0))
        ) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        return bytes
    }

    private fun base128(value: Long): ByteArray {
        val groups = mutableListOf((value and LOW_SEVEN).toByte())
        var rest = value shr SEVEN
        while (rest > 0) {
            groups.add(0, ((rest and LOW_SEVEN) or CONTINUATION).toByte())
            rest = rest shr SEVEN
        }
        return groups.toByteArray()
    }

    private fun concat(elements: Array<out ByteArray>): ByteArray =
        elements.fold(ByteArray(0)) { all, element -> all + element }

    private const val INTEGER = 0x02
    private const val BIT_STRING = 0x03
    private const val OCTET_STRING = 0x04
    private const val OID = 0x06
    private const val ENUMERATED = 0x0A
    private const val UTF8_STRING = 0x0C
    private const val UTC_TIME = 0x17
    private const val SEQUENCE = 0x30
    private const val SET = 0x31
    private const val CONTEXT_CONSTRUCTED = 0xA0
    private const val HIGH_TAG = 0x1F
    private const val SHORT_LENGTH = 0x80
    private const val LONG_LENGTH = 0x80
    private const val BYTE_MASK = 0xFF
    private const val BITS = 8
    private const val SEVEN = 7
    private const val LOW_SEVEN = 0x7FL
    private const val CONTINUATION = 0x80L
    private const val FIRST_ARC_FACTOR = 40
}
