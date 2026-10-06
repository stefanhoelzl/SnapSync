package app.snapsync.model

/**
 * **The encrypted file format** of an encrypted event's stored photo — the layout alone, pure; the primitives it is
 * sealed with are the `Crypto` port's, and the reading and writing of files the services'. Its TypeScript twin is
 * `api/src/encrypted-file.ts` (the edge encrypts the iOS extension's uploads, the event page decrypts in a browser);
 * both are held to `test/vectors/encrypted-file.json`, produced by an implementation independent of either and
 * decrypted by Google Tink.
 *
 * A file is a 9-byte SnapSync prefix, then Tink's `AesGcmHkdfStreaming` ciphertext, byte for byte:
 * ```
 * prefix   version (1 byte, 0x01) ‖ key id (8 bytes)
 * header   header length (1 byte, 40) ‖ salt (32 bytes) ‖ nonce prefix (7 bytes)
 * segments AES-256-GCM; each ciphertext segment 64 KiB (the first shorter by the header), the last shorter still;
 *          nonce = nonce prefix ‖ segment number (u32 big-endian) ‖ last (0|1)
 * ```
 * The segment key is HKDF-SHA256(event key, salt, [associatedData]): the associated data names the resource the file
 * is, so a file moved to another event, device, asset or role fails to decrypt. The key id is HKDF-SHA256(event key,
 * no salt, "snapsync/key-id/v1") cut to 8 bytes — what the event row holds, telling a wrong key from a damaged file
 * without revealing the key.
 */
object EncryptedFileFormat {
    const val VERSION: Byte = 1
    const val KEY_LENGTH = 32
    const val KEY_ID_LENGTH = 8
    const val PREFIX_LENGTH = 1 + KEY_ID_LENGTH
    const val SALT_LENGTH = 32
    const val NONCE_PREFIX_LENGTH = 7
    const val NONCE_LENGTH = 12
    const val HEADER_LENGTH = 1 + SALT_LENGTH + NONCE_PREFIX_LENGTH
    const val HEAD_LENGTH = PREFIX_LENGTH + HEADER_LENGTH
    const val TAG_LENGTH = 16
    const val SEGMENT_LENGTH = 65536

    private val KEY_ID_INFO = "snapsync/key-id/v1".encodeToByteArray()

    /** The one file's key the edge seals an iOS extension upload with — base64url, never the event key. */
    const val FILE_KEY_HEADER = "x-snapsync-file-key"

    /** That file's opening bytes (prefix and header), base64url. */
    const val FILE_HEAD_HEADER = "x-snapsync-file-head"

    /** The key id an event row holds for [eventKey]. */
    fun keyIdOf(eventKey: ByteArray, hmac: Hmac): ByteArray = Hkdf.derive(hmac, eventKey, ByteArray(0), KEY_ID_INFO, KEY_ID_LENGTH)

    /** The key one file's segments are sealed with. */
    fun fileKeyOf(eventKey: ByteArray, salt: ByteArray, associatedData: ByteArray, hmac: Hmac): ByteArray =
        Hkdf.derive(hmac, eventKey, salt, associatedData, KEY_LENGTH)

    /** What a file is bound to. The ids are validated path segments, so no `/` occurs inside one. */
    fun associatedData(eventId: String, deviceId: String, assetId: String, role: String): ByteArray =
        "snapsync/v1/$eventId/$deviceId/$assetId/$role".encodeToByteArray()

    /** How many plaintext bytes segment [segment] carries, unless it is the last. */
    fun plaintextSegmentLength(segment: Int): Int = SEGMENT_LENGTH - TAG_LENGTH - if (segment == 0) HEADER_LENGTH else 0

    /** How many ciphertext bytes segment [segment] occupies, unless it is the last. */
    fun ciphertextSegmentLength(segment: Int): Int = plaintextSegmentLength(segment) + TAG_LENGTH

    /** Segment [segment]'s nonce. */
    fun segmentNonce(noncePrefix: ByteArray, segment: Int, last: Boolean): ByteArray = ByteArray(NONCE_LENGTH).also {
        noncePrefix.copyInto(it)
        it[7] = (segment ushr 24).toByte()
        it[8] = (segment ushr 16).toByte()
        it[9] = (segment ushr 8).toByte()
        it[10] = segment.toByte()
        it[11] = if (last) 1 else 0
    }

    /** The prefix and header, as they open the file. */
    fun encodeHead(head: FileHead): ByteArray = ByteArray(HEAD_LENGTH).also {
        it[0] = VERSION
        head.keyId.copyInto(it, 1)
        it[PREFIX_LENGTH] = HEADER_LENGTH.toByte()
        head.salt.copyInto(it, PREFIX_LENGTH + 1)
        head.noncePrefix.copyInto(it, PREFIX_LENGTH + 1 + SALT_LENGTH)
    }

    /** The prefix and header of a file's first [HEAD_LENGTH] bytes, or why they are not this format. */
    fun decodeHead(bytes: ByteArray): HeadRead = when {
        bytes.size < HEAD_LENGTH -> HeadRead.Refused("truncated head")
        bytes[0] != VERSION -> HeadRead.Refused("unknown format version ${bytes[0]}")
        bytes[PREFIX_LENGTH] != HEADER_LENGTH.toByte() -> HeadRead.Refused("header length ${bytes[PREFIX_LENGTH]}")
        else -> HeadRead.Read(
            FileHead(
                keyId = bytes.copyOfRange(1, PREFIX_LENGTH),
                salt = bytes.copyOfRange(PREFIX_LENGTH + 1, PREFIX_LENGTH + 1 + SALT_LENGTH),
                noncePrefix = bytes.copyOfRange(PREFIX_LENGTH + 1 + SALT_LENGTH, HEAD_LENGTH),
            ),
        )
    }
}

/** An encrypted file's opening bytes: everything before its first segment. */
class FileHead(val keyId: ByteArray, val salt: ByteArray, val noncePrefix: ByteArray) {
    init {
        require(keyId.size == EncryptedFileFormat.KEY_ID_LENGTH) { "key id length ${keyId.size}" }
        require(salt.size == EncryptedFileFormat.SALT_LENGTH) { "salt length ${salt.size}" }
        require(noncePrefix.size == EncryptedFileFormat.NONCE_PREFIX_LENGTH) { "nonce prefix length ${noncePrefix.size}" }
    }

    override fun equals(other: Any?): Boolean =
        other is FileHead && keyId.contentEquals(other.keyId) && salt.contentEquals(other.salt) &&
            noncePrefix.contentEquals(other.noncePrefix)

    override fun hashCode(): Int = keyId.contentHashCode()
}

/** Whether a file's opening bytes are this format. */
sealed interface HeadRead {
    data class Read(val head: FileHead) : HeadRead
    data class Refused(val reason: String) : HeadRead
}

/** HMAC-SHA256 of a message under a key — the one primitive HKDF is built from. */
fun interface Hmac {
    fun mac(key: ByteArray, message: ByteArray): ByteArray
}

/** HKDF-SHA256 (RFC 5869), over a platform's [Hmac]. */
object Hkdf {
    private const val HASH_LENGTH = 32
    private const val MAX_BLOCKS = 255

    fun derive(hmac: Hmac, ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..MAX_BLOCKS * HASH_LENGTH) { "HKDF length $length" }
        val prk = hmac.mac(if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var at = 0
        var block = 1
        while (at < length) {
            previous = hmac.mac(prk, previous + info + byteArrayOf(block.toByte()))
            val take = minOf(HASH_LENGTH, length - at)
            previous.copyInto(out, at, 0, take)
            at += take
            block++
        }
        return out
    }
}
