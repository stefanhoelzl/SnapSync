package app.snapsync.services.crypto

import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.FileCipher
import app.snapsync.data.crypto.SealOutcome
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.model.Resource
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.roleFromUploadKey
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.services.identity.requireDeviceId
import app.snapsync.services.membership.MembershipProjection

/**
 * **How one upload is sealed** (the encrypted file format, `docs/architecture.md`): not at all for a plain event; for an
 * encrypted one, under the joined event's key and bound to the resource it is — the event, this device, the asset and
 * its role, exactly as the edge names the stored object.
 *
 * Never plaintext into an encrypted event: when the key cannot be read (a locked device), is gone, or is not the one
 * the event names, the answer is [UploadSeal.Withheld] and no upload starts — the next cycle asks again.
 */
class UploadSealing(
    private val keys: EventKeyStore,
    private val cipher: FileCipher,
    private val config: MembershipProjection,
    private val identity: DeviceIdentity,
) {

    /** How [resource] goes up in the joined event. */
    suspend fun sealFor(resource: Resource): UploadSeal {
        val joined = config.joinedOrRead() ?: return UploadSeal.Plain
        val keyId = joined.keyId ?: return UploadSeal.Plain
        val key = when (val read = keys.kept()) {
            is Kept.Key -> read.key
            Kept.Unreadable -> return UploadSeal.Withheld("the event key cannot be read now")
            Kept.None -> return UploadSeal.Withheld("no event key is kept")
        }
        if (key.id.value != keyId) return UploadSeal.Withheld("the kept key is not the event's")
        val binding = FileBinding(
            joined.eventId,
            identity.requireDeviceId(),
            assetIdFromUploadKey(resource.filename),
            roleFromUploadKey(resource.filename),
        )
        return UploadSeal.Sealed(key, binding)
    }

    /** Seal [from] into [to] in the shared area — a platform that uploads files sends the sealed one. */
    fun seal(seal: UploadSeal.Sealed, from: String, to: String): FileResult<Unit> =
        when (val sealed = cipher.seal(seal.key, seal.binding, FileArea.SHARED, from, to)) {
            is SealOutcome.Sealed -> FileResult.Ok(Unit)
            is SealOutcome.Failed -> sealed.result
        }

    /**
     * The headers that let the edge seal the library's own bytes — for a platform that cannot hand over a file
     * (PhotoKit). They carry THIS file's key and opening bytes, never the event key, and are never logged.
     */
    fun edgeHeaders(seal: UploadSeal.Sealed): Map<String, String> = cipher.edgeSeal(seal.key, seal.binding).headers()
}

/** How one upload goes up. */
sealed interface UploadSeal {
    /** A plain event: the bytes as the library holds them. */
    data object Plain : UploadSeal

    /** An encrypted event: sealed under [key], bound to [binding]. */
    class Sealed(val key: IdentifiedKey, val binding: FileBinding) : UploadSeal {
        override fun toString(): String = "Sealed"
    }

    /** An encrypted event whose key is not at hand: nothing goes up now. */
    data class Withheld(val reason: String) : UploadSeal
}
