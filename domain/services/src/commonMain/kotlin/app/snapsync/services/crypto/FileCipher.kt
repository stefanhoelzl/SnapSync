package app.snapsync.services.crypto

import app.snapsync.model.EncryptedFileFormat
import app.snapsync.model.EncryptedFileFormat.HEAD_LENGTH
import app.snapsync.model.FileArea
import app.snapsync.model.FileHead
import app.snapsync.model.FileResult
import app.snapsync.model.HeadRead
import app.snapsync.model.Hmac
import app.snapsync.ports.Crypto
import app.snapsync.ports.Files

/**
 * **An encrypted event's files, sealed and opened** — the encrypted file format ([EncryptedFileFormat]) over the
 * platform's primitives ([Crypto]) and its files ([Files]). A file is read and written one 64 KiB segment at a time,
 * so a video costs the same memory as a photo; the output is built under `<to>.part` and moved into place only once
 * whole, so a reader never meets half a file and an interrupted run leaves nothing at `<to>`.
 *
 * What is never here: where a key comes from or is kept, and what a failure means to the event — this answers what
 * happened to one file, and its callers decide.
 */
class FileCipher(private val crypto: Crypto, private val files: Files) {

    private val hmac = Hmac(crypto::hmacSha256)

    /** The key id an event row holds for [eventKey]. */
    fun keyIdOf(eventKey: ByteArray): ByteArray = EncryptedFileFormat.keyIdOf(eventKey, hmac)

    /**
     * What a file sealed elsewhere needs — the iOS upload extension's, which the edge encrypts as it streams: a fresh
     * head and the key of that one file, never the event key, so the edge learns nothing it could open another file
     * with.
     */
    fun sealedElsewhere(eventKey: ByteArray, associatedData: ByteArray): ElsewhereSeal {
        val head = freshHead(eventKey)
        return ElsewhereSeal(
            EncryptedFileFormat.encodeHead(head),
            EncryptedFileFormat.fileKeyOf(eventKey, head.salt, associatedData, hmac),
        )
    }

    /** Encrypt [from] into [to], both in [area], under [eventKey], bound to [associatedData]. [from] is left as it was. */
    fun encrypt(
        eventKey: ByteArray,
        associatedData: ByteArray,
        area: FileArea,
        from: String,
        to: String,
    ): FileResult<Unit> {
        val head = freshHead(eventKey)
        val key = EncryptedFileFormat.fileKeyOf(eventKey, head.salt, associatedData, hmac)
        return building(area, to) { part ->
            files.append(area, part, EncryptedFileFormat.encodeHead(head)).failure()?.let { return@building it }
            segments(
                area,
                from,
                start = 0L,
                length = EncryptedFileFormat::plaintextSegmentLength,
            ) { segment, plain, last ->
                files.append(
                    area,
                    part,
                    crypto.aesGcmSeal(key, EncryptedFileFormat.segmentNonce(head.noncePrefix, segment, last), plain),
                )
            }
        }
    }

    /**
     * Decrypt [from] into [to], both in [area], with [eventKey], expecting it bound to [associatedData]. Nothing is
     * written to [to] unless EVERY segment authenticated: a truncated, reordered, moved or altered file yields no byte.
     */
    fun decrypt(eventKey: ByteArray, associatedData: ByteArray, area: FileArea, from: String, to: String): Opened {
        val headBytes = when (val read = files.readRange(area, from, 0, HEAD_LENGTH)) {
            is FileResult.Ok -> read.value
            else -> return Opened.Unreadable(read.failure()!!)
        }
        val head = when (val decoded = EncryptedFileFormat.decodeHead(headBytes)) {
            is HeadRead.Read -> decoded.head
            is HeadRead.Refused -> return Opened.Damaged(decoded.reason)
        }
        if (!head.keyId.contentEquals(keyIdOf(eventKey))) return Opened.OtherKey
        val key = EncryptedFileFormat.fileKeyOf(eventKey, head.salt, associatedData, hmac)
        var damage: String? = null
        val written = building(area, to) { part ->
            segments(
                area,
                from,
                start = HEAD_LENGTH.toLong(),
                length = EncryptedFileFormat::ciphertextSegmentLength,
            ) { segment, sealed, last ->
                val plain = crypto.aesGcmOpen(
                    key,
                    EncryptedFileFormat.segmentNonce(head.noncePrefix, segment, last),
                    sealed,
                )
                if (plain == null) {
                    damage = "segment $segment failed to authenticate"
                    FileResult.Failed("segment $segment failed to authenticate")
                } else {
                    files.append(area, part, plain)
                }
            }
        }
        damage?.let { return Opened.Damaged(it) }
        return written.failure()?.let { Opened.Unreadable(it) } ?: Opened.Ok
    }

    /**
     * Hand [each] the segments of [path] from [start] on, [length] bytes each but the last, which is whatever remains.
     * A segment is known to be the last when reading one byte past it finds nothing — so the end of the file alone
     * decides, as the format requires. Stops at the first failure [each] or a read answers.
     */
    private inline fun segments(
        area: FileArea,
        path: String,
        start: Long,
        length: (segment: Int) -> Int,
        each: (segment: Int, bytes: ByteArray, last: Boolean) -> FileResult<Unit>,
    ): FileResult<Unit> {
        var offset = start
        var segment = 0
        var last = false
        while (!last) {
            val size = length(segment)
            val read = when (val r = files.readRange(area, path, offset, size + 1)) {
                is FileResult.Ok -> r.value
                else -> return r.failure()!!
            }
            last = read.size <= size
            each(segment, if (last) read else read.copyOf(size), last).failure()?.let { return it }
            offset += size
            segment++
        }
        return FileResult.Ok(Unit)
    }

    private fun freshHead(eventKey: ByteArray) = FileHead(
        keyId = keyIdOf(eventKey),
        salt = crypto.randomBytes(EncryptedFileFormat.SALT_LENGTH),
        noncePrefix = crypto.randomBytes(EncryptedFileFormat.NONCE_PREFIX_LENGTH),
    )

    /** Run [build] into `<to>.part`, then move it to [to]; on any failure the part is removed and [to] untouched. */
    private inline fun building(
        area: FileArea,
        to: String,
        build: (part: String) -> FileResult<Unit>,
    ): FileResult<Unit> {
        val part = "$to.part"
        files.delete(area, part)
        val built = build(part)
        val placed = if (built is FileResult.Ok) files.move(area, part, to) else built
        if (placed !is FileResult.Ok) files.delete(area, part)
        return placed
    }

    /** This result as a failure, or `null` when it is [FileResult.Ok]. */
    private fun FileResult<*>.failure(): FileResult<Nothing>? = when (this) {
        is FileResult.Ok -> null
        is FileResult.NotFound -> this
        is FileResult.AreaUnavailable -> this
        is FileResult.Denied -> this
        is FileResult.Failed -> this
    }
}

/** What the edge needs to seal one file of the iOS extension's: its opening bytes, and that one file's key. */
class ElsewhereSeal(val head: ByteArray, val fileKey: ByteArray)

/** What became of one decryption. */
sealed interface Opened {
    /** Every segment authenticated; the plaintext is in place. */
    data object Ok : Opened

    /** The file names another event key — a wrong key, not a damaged file. */
    data object OtherKey : Opened

    /** Not this format, or a segment failed to authenticate: no byte was written. */
    data class Damaged(val reason: String) : Opened

    /** The file could not be read or the plaintext not written. */
    data class Unreadable(val result: FileResult<Nothing>) : Opened
}
