package app.snapsync.data.crypto

import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.format.EnvelopeRead
import app.snapsync.format.MetaRecord
import app.snapsync.format.SealedMetaFormat
import app.snapsync.ports.AesKey
import app.snapsync.ports.Crypto
import app.snapsync.ports.GcmNonce

/**
 * **One sealed metadata record, sealed and opened under a key already derived** — the sealed metadata format's
 * envelope ([SealedMetaFormat]) over the platform's primitives, whatever the record is of: an event's own
 * ([MetaCipher]) or one photo's file name ([FileNameCipher]), each deriving its key under its own label. A seal draws
 * a fresh nonce every time (a record is sealed once per create, rename or upload, nowhere near GCM's bound).
 */
class SealedRecords(private val crypto: Crypto) {
    /** [record], sealed under [key], as the wire and the row carry it. */
    fun seal(key: AesKey, record: MetaRecord): String {
        val nonce = crypto.randomBytes(SealedMetaFormat.NONCE_LENGTH)
        val sealed = crypto.aesGcmSeal(key, nonceOf(nonce), SealedMetaFormat.plaintextOf(record))
        return SealedMetaFormat.encode(nonce, sealed)
    }

    /** The record [envelope] holds, opened with [key], or why it does not open. */
    fun open(key: AesKey, envelope: String): MetaOpening = when (val read = SealedMetaFormat.decode(envelope)) {
        is EnvelopeRead.Refused -> MetaOpening.Damaged(read.reason)
        is EnvelopeRead.Read -> opened(crypto.aesGcmOpen(key, nonceOf(read.nonce), read.sealed))
    }

    /** The AES key [derived] is — the format derives exactly [SealedMetaFormat.KEY_LENGTH] bytes. */
    fun keyOf(derived: ByteArray): AesKey {
        val exact = EncryptedFileFormat.ofLength(derived, SealedMetaFormat.KEY_LENGTH)
        return AesKey(exact ?: throw InvalidProgramState("the sealed metadata format derives a 32-byte key"))
    }

    private fun opened(plaintext: ByteArray?): MetaOpening =
        plaintext?.let(SealedMetaFormat::recordOf)?.let(MetaOpening::Opened)
            ?: MetaOpening.Damaged("does not open under this key, or holds no name")

    private fun nonceOf(bytes: ByteArray): GcmNonce {
        val exact = EncryptedFileFormat.ofLength(bytes, SealedMetaFormat.NONCE_LENGTH)
        return GcmNonce(exact ?: throw InvalidProgramState("the sealed metadata format draws a 12-byte nonce"))
    }
}

/** What became of opening one record. */
sealed interface MetaOpening {
    /** It authenticated under its key, and holds a name. */
    class Opened(val record: MetaRecord) : MetaOpening

    /** Not this format, sealed under another key, altered, or holding no name. */
    data class Damaged(val reason: String) : MetaOpening
}
