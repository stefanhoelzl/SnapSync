package app.snapsync.services.crypto

import app.snapsync.model.EncryptedFileFormat
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.Resource
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.encodeEventKey
import app.snapsync.model.roleFromUploadKey
import app.snapsync.services.config.ConfigService
import app.snapsync.services.identity.PersistedDeviceIdentity

/**
 * **How one upload is sealed** (the encrypted file format, `docs/architecture.md`): not at all for a plain event; for an
 * encrypted one, under the joined event's key and bound to the resource it is — the event, this device, the asset and
 * its role, exactly as the edge names the stored object.
 *
 * Never plaintext into an encrypted event: when the key cannot be read (a locked device), is gone, or is not the one
 * the event names, the answer is [UploadSeal.Withheld] and no upload starts — the next cycle asks again.
 */
class UploadSealing(
    private val keys: EventKeys,
    private val cipher: FileCipher,
    private val config: ConfigService,
    private val identity: PersistedDeviceIdentity,
) {

    /** How [resource] goes up in the joined event. */
    suspend fun sealFor(resource: Resource): UploadSeal {
        val joined = config.joinedOrRead() ?: return UploadSeal.Plain
        val keyId = joined.keyId ?: return UploadSeal.Plain
        val key = try {
            keys.current()
        } catch (locked: SecureStoreUnavailable) {
            return UploadSeal.Withheld("the event key cannot be read now: ${locked.detail}")
        } ?: return UploadSeal.Withheld("no event key is kept")
        if (cipher.keyIdOf(key).toHex() != keyId) return UploadSeal.Withheld("the kept key is not the event's")
        val ad = EncryptedFileFormat.associatedData(
            joined.eventId,
            identity.deviceId(),
            assetIdFromUploadKey(resource.filename).value,
            roleFromUploadKey(resource.filename).wire,
        )
        return UploadSeal.Sealed(key, ad)
    }

    /** Seal [from] into [to] in the shared area — a platform that uploads files sends the sealed one. */
    fun seal(seal: UploadSeal.Sealed, from: String, to: String): FileResult<Unit> =
        cipher.encrypt(seal.eventKey, seal.associatedData, FileArea.SHARED, from, to)

    /**
     * The headers that let the edge seal the library's own bytes — for a platform that cannot hand over a file
     * (PhotoKit). They carry THIS file's key and opening bytes, never the event key, and are never logged.
     */
    fun edgeHeaders(seal: UploadSeal.Sealed): Map<String, String> {
        val elsewhere = cipher.sealedElsewhere(seal.eventKey, seal.associatedData)
        return mapOf(
            EncryptedFileFormat.FILE_KEY_HEADER to encodeEventKey(elsewhere.fileKey),
            EncryptedFileFormat.FILE_HEAD_HEADER to encodeEventKey(elsewhere.head),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** How one upload goes up. */
sealed interface UploadSeal {
    /** A plain event: the bytes as the library holds them. */
    data object Plain : UploadSeal

    /** An encrypted event: sealed under [eventKey], bound to [associatedData]. */
    class Sealed(val eventKey: ByteArray, val associatedData: ByteArray) : UploadSeal {
        override fun toString(): String = "Sealed"
    }

    /** An encrypted event whose key is not at hand: nothing goes up now. */
    data class Withheld(val reason: String) : UploadSeal
}
