package app.snapsync.data.crypto

import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.KeyMaterial
import app.snapsync.domain.receiving.SenderFilename
import app.snapsync.format.SealedMetaFormat
import app.snapsync.ports.AesKey
import app.snapsync.ports.Crypto

/**
 * **A photo's file name in an encrypted event, sealed and opened** (decision record `changes/seal-file-names`, D1):
 * the sealed metadata record `{"name": …}` under a key derived for that one photo — its event, device, asset and role
 * — so a record moved onto another photo opens under no key there, as a moved file does not decrypt.
 */
@OptIn(KeyMaterial::class)
class FileNameCipher(crypto: Crypto) {
    private val records = SealedRecords(crypto)
    private val hmac = CryptoHmac(crypto)

    /** [name], sealed under the event's [key] for the photo [binding] names alone, as the upload's query carries it. */
    fun sealFileName(key: IdentifiedKey, binding: FileBinding, name: String): String =
        records.seal(keyOf(key, binding), SealedMetaFormat.recordNamed(name))

    /**
     * The file name [envelope] holds for the photo [binding] names, opened with the event's [key] — or why it does not
     * open. One naming no file is damaged: every upload names its file.
     */
    fun openFileName(key: IdentifiedKey, binding: FileBinding, envelope: String): FileNameOpening =
        when (val opening = records.open(keyOf(key, binding), envelope)) {
            is MetaOpening.Damaged -> FileNameOpening.Damaged(opening.reason)
            is MetaOpening.Opened -> SenderFilename.parse(opening.record.name)?.let(FileNameOpening::Opened)
                ?: FileNameOpening.Damaged("names no file")
        }

    private fun keyOf(key: IdentifiedKey, binding: FileBinding): AesKey =
        records.keyOf(SealedMetaFormat.fileNameKeyOf(key.key.bytes(), binding, hmac))
}

/** What became of opening one photo's sealed file name. */
sealed interface FileNameOpening {
    /** It authenticated as this photo's, and names its file. */
    class Opened(val name: SenderFilename) : FileNameOpening

    /** Not this format, sealed for another photo or under another key, altered, or naming no file. */
    data class Damaged(val reason: String) : FileNameOpening
}
