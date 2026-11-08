package app.snapsync.data.upload

import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.ports.Crypto
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileFailure
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files

/**
 * **A staged file's digest** — its SHA-256 and size, beside it as `<sha256 hex> <size>`: written when the file is
 * staged, read when a failed attempt is sent again, and dropped once its file is gone.
 */
class StagedDigests(private val files: Files, private val crypto: Crypto, private val staged: StagedFiles) {
    private val log = Log("StagedDigests")

    /** A digest file's one line: `<sha256 hex> <size>`. */
    private val digestLine = Regex("^([0-9a-f]{64}) ([0-9]+)$")

    /** The digest a failed attempt kept beside [key]'s staged file — only while that file is still there. */
    fun kept(key: String): StagedDigest? = when (val read = files.read(FileArea.SHARED, staged.digestPath(key))) {
        is FileResult.Ok -> whileStaged(key, parsed(read.value.decodeToString()))
        is FileFailure -> null
    }

    /**
     * Hash [key]'s staged file and keep the digest beside it — or `null` when the file could not be read whole: the
     * upload then goes as it always did, and starts over if it fails.
     */
    fun digest(key: String): StagedDigest? {
        val chunks = Chunks(staged.path(key))
        val hash = crypto.sha256(chunks.read())
        return when (chunks.whole) {
            true -> written(key, StagedDigest(hex(hash), chunks.size))
            false -> unhashed(key)
        }
    }

    private fun parsed(text: String): StagedDigest? =
        digestLine.matchEntire(text)?.let { StagedDigest(it.groupValues[1], it.groupValues[2].toLong()) }

    private fun whileStaged(key: String, digest: StagedDigest?): StagedDigest? = when (digest) {
        null -> dropped(key)
        is StagedDigest -> whenStaged(key, digest)
    }

    private fun whenStaged(key: String, digest: StagedDigest): StagedDigest? = when (staged.staged(key)) {
        true -> digest
        false -> dropped(key)
    }

    private fun dropped(key: String): StagedDigest? {
        files.delete(FileArea.SHARED, staged.digestPath(key))
        return null
    }

    private fun written(key: String, digest: StagedDigest): StagedDigest {
        files.write(FileArea.SHARED, staged.digestPath(key), "${digest.sha256} ${digest.size}".encodeToByteArray())
        return digest
    }

    private fun unhashed(key: String): StagedDigest? {
        log.unlabelled.w("a staged upload file could not be hashed — it will not resume", "key" of key)
        return null
    }

    private fun hex(hash: ByteArray): String =
        hash.joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }

    /** A staged file read in chunks for its hash — [whole] once every chunk was read, [size] its bytes. */
    private inner class Chunks(private val path: String) {
        var whole = true
            private set
        var size = 0L
            private set

        fun read(): Sequence<ByteArray> =
            generateSequence(0L) { it + CHUNK }.map(::chunk).takeWhile { it.isNotEmpty() }.onEach { size += it.size }

        private fun chunk(offset: Long): ByteArray =
            when (val read = files.readRange(FileArea.SHARED, path, offset, CHUNK)) {
                is FileResult.Ok -> read.value
                is FileFailure -> unread()
            }

        private fun unread(): ByteArray {
            whole = false
            return ByteArray(0)
        }
    }

    private companion object {
        const val CHUNK = 1 shl 20
        const val BYTE = 0xff
        const val HEX = 16
    }
}

/** A staged file's SHA-256 (lowercase hex) and size. */
class StagedDigest(val sha256: String, val size: Long)
