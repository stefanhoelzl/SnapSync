package app.snapsync.data.crypto

import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.event.SealVerdict
import app.snapsync.domain.membership.Membership
import app.snapsync.ports.FileArea

/**
 * **How one upload is sealed** (the encrypted file format, `docs/architecture.md`): not at all for a plain event;
 * for an encrypted one, under the event's key and bound to the resource it is — the event, this device, the asset and
 * its role, exactly as the edge names the stored object. Never plaintext into an encrypted event, and never for another
 * event than the one the bytes go to ([SealVerdict]).
 */
class UploadSealing(
    private val membership: MembershipRepository,
    private val keys: EventKeyStore,
    private val identity: DeviceIdentity,
    private val cipher: FileCipher,
) {
    /** How [asset]'s [role] goes up to [event] — the event the cycle's requests address. */
    fun sealFor(event: EventId, asset: AssetId, role: ResourceRole): UploadSeal {
        val held = held(membership.state.value)
        val verdict = SealVerdict.of(held, event, KeyPresence.of(held, keys.state.value), device(identity.id()))
        return sealed(verdict, event, asset, role)
    }

    /** Seal [from] into [to] in the shared area — a platform that uploads files sends the sealed one. */
    fun seal(order: SealOrder, from: String, to: String): SealOutcome =
        cipher.seal(order.key, order.binding, FileArea.SHARED, from, to)

    /**
     * What the edge seals the library's own bytes with — for a platform that cannot hand over a file (PhotoKit): THIS
     * file's key and opening bytes, never the event key.
     */
    fun edgeSeal(order: SealOrder): EdgeSeal = cipher.edgeSeal(order.key, order.binding)
}

/** How one upload goes up. */
sealed interface UploadSeal {
    /** A plain event: the bytes as the library holds them. */
    data object Plain : UploadSeal

    /** An encrypted event: sealed as [order] says. */
    class Sealed(val order: SealOrder) : UploadSeal

    /** Nothing goes up now, [because]. */
    class Withheld(val because: SealVerdict.Withheld.Because) : UploadSeal
}

/** One resource's seal: the key and what the file is bound to. Never rendered but by its asset. */
class SealOrder internal constructor(val key: IdentifiedKey, val binding: FileBinding) {
    override fun toString(): String = "SealOrder(${binding.asset})"
}

private fun sealed(verdict: SealVerdict, event: EventId, asset: AssetId, role: ResourceRole): UploadSeal =
    when (verdict) {
        is SealVerdict.Plain -> UploadSeal.Plain
        is SealVerdict.Sealed -> UploadSeal.Sealed(
            SealOrder(verdict.key, FileBinding(event, verdict.device, asset, role)),
        )
        is SealVerdict.Withheld -> UploadSeal.Withheld(verdict.because)
    }

internal fun held(read: MembershipRead): Membership? = when (read) {
    is MembershipRead.Joined -> read.membership
    is MembershipRead.NotJoined, is MembershipRead.Unavailable -> null
}

private fun device(read: DeviceIdRead): DeviceId? = when (read) {
    is DeviceIdRead.Ok -> read.value
    is DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> null
}
