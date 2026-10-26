package app.snapsync.data.crypto

import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.KeyMaterial
import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.format.EncryptedFileFormat.HEAD_LENGTH
import app.snapsync.format.EventKeyIdText
import app.snapsync.format.FileHead
import app.snapsync.format.HeadRead
import app.snapsync.ports.AesKey
import app.snapsync.ports.Crypto
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileFailure
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import app.snapsync.ports.GcmNonce

/**
 * **An encrypted event's files, sealed and opened** — the encrypted file format over the platform's primitives
 * ([Crypto]) and its files ([Files]), each file bound to the resource it is ([FileBinding]). A file is read and written
 * one 64 KiB segment at a time, so a video costs the same memory as a photo; the output is built under `<to>.part` and
 * moved into place only once whole, so a reader never meets half a file and an interrupted run leaves nothing at
 * `<to>`. Nothing is written to an opened file's `<to>` unless EVERY segment authenticated: a truncated, reordered,
 * moved or altered file yields no byte.
 *
 * What is never here: where a key comes from or is kept, and what a failure means to the event — this answers what
 * happened to one file, and its callers decide.
 */
@OptIn(KeyMaterial::class)
class FileCipher(private val crypto: Crypto, private val files: Files) {
    private val hmac = CryptoHmac(crypto)
    private val segments = Segments(files)

    /** Seal [from] into [to], both in [area], under [key], bound to [binding]. [from] is left as it was. */
    fun seal(key: IdentifiedKey, binding: FileBinding, area: FileArea, from: String, to: String): SealOutcome {
        val head = freshHead(key)
        val part = Part(files, area, to).begun()
        return sealed(part.placed(sealedInto(part, fileKey(key, head, binding), head, from)))
    }

    /** Open [from] into [to], both in [area], with [key], expecting it bound to [binding]. */
    fun open(key: IdentifiedKey, binding: FileBinding, area: FileArea, from: String, to: String): OpenOutcome =
        when (val read = files.readRange(area, from, 0, HEAD_LENGTH)) {
            is FileResult.Ok -> headed(
                Opening(key, binding, area, from, to),
                EncryptedFileFormat.decodeHead(read.value),
            )
            is FileFailure -> OpenOutcome.Unreadable(read)
        }

    /**
     * What a file sealed elsewhere needs — the iOS upload extension's, which the edge encrypts as it streams: a fresh
     * head and the key of that one file, never the event key, so the edge learns nothing it could open another file
     * with.
     */
    fun edgeSeal(key: IdentifiedKey, binding: FileBinding): EdgeSeal = freshHead(key).let { head ->
        EdgeSeal(EncryptedFileFormat.encodeHead(head), fileKey(key, head, binding))
    }

    private fun sealedInto(part: Part, fileKey: AesKey, head: FileHead, from: String): FileResult<Unit> =
        when (val written = files.append(part.area, part.path, EncryptedFileFormat.encodeHead(head))) {
            is FileResult.Ok -> segments.walk(part.area, from, 0L, Plaintext, Sealing(crypto, part, fileKey, head))
            is FileFailure -> written
        }

    private fun headed(opening: Opening, head: HeadRead): OpenOutcome = when (head) {
        is HeadRead.Read -> keyed(opening, head.head, EventKeyIdText.of(head.head.keyId) == opening.key.id)
        is HeadRead.Refused -> OpenOutcome.Damaged(head.reason)
    }

    private fun keyed(opening: Opening, head: FileHead, ours: Boolean): OpenOutcome = when (ours) {
        true -> opened(opening, head, Part(files, opening.area, opening.to).begun())
        false -> OpenOutcome.OtherKey
    }

    private fun opened(opening: Opening, head: FileHead, part: Part): OpenOutcome = part.placed(
        segments.walk(
            opening.area,
            opening.from,
            HEAD_LENGTH.toLong(),
            Ciphertext,
            Unsealing(crypto, part, fileKey(opening.key, head, opening.binding), head),
        ),
    )

    private fun freshHead(key: IdentifiedKey): FileHead {
        val head = FileHead.of(
            keyId = EncryptedFileFormat.keyIdOf(key.key.bytes(), hmac),
            salt = crypto.randomBytes(EncryptedFileFormat.SALT_LENGTH),
            noncePrefix = crypto.randomBytes(EncryptedFileFormat.NONCE_PREFIX_LENGTH),
        )
        return head ?: throw InvalidProgramState("the format's key id and the draws are of the head's lengths")
    }

    private fun fileKey(key: IdentifiedKey, head: FileHead, binding: FileBinding): AesKey = aesKeyOf(
        EncryptedFileFormat.fileKeyOf(key.key.bytes(), head.salt, EncryptedFileFormat.associatedData(binding), hmac),
    )
}

/** What became of one seal. */
sealed interface SealOutcome {
    /** Every segment is written, and the file is in place. */
    data object Sealed : SealOutcome

    /** [result] is why not: nothing is at `<to>`. */
    class Failed(val result: FileFailure) : SealOutcome
}

/** What became of one opening. */
sealed interface OpenOutcome {
    /** Every segment authenticated; the plaintext is in place. */
    data object Opened : OpenOutcome

    /** The file names another event key — a wrong key, not a damaged file. */
    data object OtherKey : OpenOutcome

    /** Not this format, or a segment failed to authenticate: no byte was written. */
    class Damaged(val reason: String) : OpenOutcome

    /** The file could not be read or the plaintext not written. */
    class Unreadable(val result: FileFailure) : OpenOutcome
}

/**
 * What the edge needs to seal one file the iOS extension sends: its opening bytes, and that one file's own key — never
 * the event key. Never rendered.
 */
class EdgeSeal internal constructor(private val head: ByteArray, private val fileKey: AesKey) {
    /** The two headers that carry it: the file key and the file head, as the format names them. */
    fun headers(): Map<String, String> = mapOf(
        EncryptedFileFormat.FILE_KEY_HEADER to EncryptedFileFormat.headerValue(fileKey.bytes),
        EncryptedFileFormat.FILE_HEAD_HEADER to EncryptedFileFormat.headerValue(head),
    )

    override fun toString(): String = "EdgeSeal(***)"
}

/** An opening's facts. */
private class Opening(
    val key: IdentifiedKey,
    val binding: FileBinding,
    val area: FileArea,
    val from: String,
    val to: String,
)

private fun sealed(placed: FileResult<Unit>): SealOutcome = when (placed) {
    is FileResult.Ok -> SealOutcome.Sealed
    is FileFailure -> SealOutcome.Failed(placed)
}

/** A file key, as the format derives it: [EncryptedFileFormat.KEY_LENGTH] bytes. */
private fun aesKeyOf(bytes: ByteArray): AesKey {
    val exact = EncryptedFileFormat.ofLength(bytes, EncryptedFileFormat.KEY_LENGTH)
    return AesKey(exact ?: throw InvalidProgramState("the format derives a 32-byte file key"))
}

/** Segment [segment]'s nonce, as the format builds it: [EncryptedFileFormat.NONCE_LENGTH] bytes. */
internal fun nonceOf(head: FileHead, segment: Int, last: Boolean): GcmNonce {
    val bytes = EncryptedFileFormat.segmentNonce(head.noncePrefix, segment, last)
    val exact = EncryptedFileFormat.ofLength(bytes, EncryptedFileFormat.NONCE_LENGTH)
    return GcmNonce(exact ?: throw InvalidProgramState("the format builds a 12-byte nonce"))
}
