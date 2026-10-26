package app.snapsync.format

import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.program.InvalidProgramState
import kotlin.io.encoding.Base64

/**
 * **The encrypted file format** of an encrypted event's stored photo — the layout alone, in bytes, naming no port: the
 * primitives it is sealed with are the `Crypto` port's, and the reading and writing of files the data layer's
 * (`FileCipher`). Its TypeScript twin is
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
 * is (a [FileBinding]), so a file moved to another event, device, asset or role fails to decrypt. The key id is HKDF-SHA256(event key,
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

    private val headerEncoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** [bytes] as [FILE_KEY_HEADER] and [FILE_HEAD_HEADER] carry them: base64url, unpadded. */
    fun headerValue(bytes: ByteArray): String = headerEncoder.encode(bytes)

    /** The key id an event row holds for [eventKey]. */
    fun keyIdOf(eventKey: ByteArray, hmac: Hmac): ByteArray = Hkdf.derive(
        hmac,
        eventKey,
        ByteArray(0),
        KEY_ID_INFO,
        KEY_ID_LENGTH,
    )

    /** The key one file's segments are sealed with: [KEY_LENGTH] bytes. */
    fun fileKeyOf(eventKey: ByteArray, salt: ByteArray, associatedData: ByteArray, hmac: Hmac): ByteArray =
        Hkdf.derive(hmac, eventKey, salt, associatedData, KEY_LENGTH)

    /**
     * What a file bound to [binding] is sealed with as its associated data — the one place it is spelled. The ids are
     * validated path segments, so no `/` occurs inside one.
     */
    fun associatedData(binding: FileBinding): ByteArray =
        "snapsync/v1/${binding.event}/${binding.device}/${binding.asset}/${ResourceRoleText.of(binding.role)}"
            .encodeToByteArray()

    /** How many plaintext bytes segment [segment] carries, unless it is the last. */
    fun plaintextSegmentLength(segment: Int): Int = SEGMENT_LENGTH - TAG_LENGTH - if (segment == 0) HEADER_LENGTH else 0

    /** How many ciphertext bytes segment [segment] occupies, unless it is the last. */
    fun ciphertextSegmentLength(segment: Int): Int = plaintextSegmentLength(segment) + TAG_LENGTH

    /**
     * A segment's bytes from [read] — one byte more than a not-last segment's [length], asked for so the end of the file
     * alone decides: finding nothing past [length] bytes makes it the last, whatever remains; otherwise it is [length]
     * bytes and more follow.
     */
    fun segmentOf(read: ByteArray, length: Int): SegmentRead =
        if (read.size <= length) SegmentRead.Last(read) else SegmentRead.More(read.copyOf(length))

    /**
     * Segment [segment]'s nonce, [NONCE_LENGTH] bytes: [noncePrefix] (a [FileHead]'s, [NONCE_PREFIX_LENGTH] bytes) ‖
     * segment ‖ last.
     */
    fun segmentNonce(noncePrefix: ByteArray, segment: Int, last: Boolean): ByteArray = ByteArray(NONCE_LENGTH).also {
        noncePrefix.copyInto(it, endIndex = NONCE_PREFIX_LENGTH)
        it[7] = (segment ushr 24).toByte()
        it[8] = (segment ushr 16).toByte()
        it[9] = (segment ushr 8).toByte()
        it[10] = segment.toByte()
        it[11] = if (last) 1 else 0
    }

    /** [bytes] when they are exactly [length] long — the format's own lengths, checked where they are handed on. */
    fun ofLength(bytes: ByteArray, length: Int): ByteArray? = bytes.takeIf { it.size == length }

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

/** An encrypted file's opening bytes: everything before its first segment, each part of its exact length. */
class FileHead internal constructor(val keyId: ByteArray, val salt: ByteArray, val noncePrefix: ByteArray) {

    override fun equals(other: Any?): Boolean =
        other is FileHead && keyId.contentEquals(other.keyId) && salt.contentEquals(other.salt) &&
            noncePrefix.contentEquals(other.noncePrefix)

    override fun hashCode(): Int = keyId.contentHashCode()

    companion object {
        /** The head of these parts, or `null` unless each is of its length ([EncryptedFileFormat]'s constants). */
        fun of(keyId: ByteArray, salt: ByteArray, noncePrefix: ByteArray): FileHead? = when {
            keyId.size != EncryptedFileFormat.KEY_ID_LENGTH -> null
            salt.size != EncryptedFileFormat.SALT_LENGTH -> null
            noncePrefix.size != EncryptedFileFormat.NONCE_PREFIX_LENGTH -> null
            else -> FileHead(keyId, salt, noncePrefix)
        }
    }
}

/** One segment, as the end of the file decides it: the last, or one more follow. */
sealed interface SegmentRead {
    class Last(val bytes: ByteArray) : SegmentRead

    class More(val bytes: ByteArray) : SegmentRead
}

/** Whether a file's opening bytes are this format. */
sealed interface HeadRead {
    data class Read(val head: FileHead) : HeadRead
    data class Refused(val reason: String) : HeadRead
}

/** HMAC-SHA256 of a message under a key — the one primitive HKDF is built from, which data adapts over the port. */
interface Hmac {
    fun mac(key: ByteArray, message: ByteArray): ByteArray
}

/** HKDF-SHA256 (RFC 5869), over a platform's [Hmac]. */
object Hkdf {
    private const val HASH_LENGTH = 32
    private const val MAX_BLOCKS = 255

    /** [length] bytes of output keying material; [length] is one of the format's own, never more than 255 blocks. */
    fun derive(hmac: Hmac, ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        if (length !in 0..MAX_BLOCKS * HASH_LENGTH) throw InvalidProgramState("HKDF derives only the format's lengths")
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
