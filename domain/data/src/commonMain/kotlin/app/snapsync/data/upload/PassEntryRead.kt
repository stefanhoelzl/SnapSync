package app.snapsync.data.upload

import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.upload.GateRead

/**
 * **What an upload pass's entry reads of the device** — the manifest version FIRST, then the membership and the device
 * id, and the key's presence for the membership read. A failed read is "I could not look", never "not joined", and its
 * forensics are the one line that makes an unreadable membership visible on a device.
 */
class PassEntryRead(
    private val rows: OwnUploadRows,
    private val membership: MembershipRepository,
    private val identity: DeviceIdentity,
    private val keys: EventKeyStore,
) {
    /** The entry's reads, made now. */
    fun read(): PassEntry {
        val version = rows.manifestVersion()
        val read = gateRead(version, membership.read(), identity.id())
        return PassEntry(read, KeyPresence.of(held(read), keys.state.value))
    }

    private fun gateRead(version: VersionRead, read: MembershipRead, id: DeviceIdRead): GateRead = when (version) {
        is VersionRead.Read -> versioned(version.version, read, id)
        is VersionRead.Unavailable ->
            unreadable(
                listOfNotNull(
                    "manifest version unreadable (${version.detail})",
                    membershipFailure(read),
                    identityFailure(id),
                ),
            )
    }

    private fun versioned(version: Long, read: MembershipRead, id: DeviceIdRead): GateRead = when (read) {
        is MembershipRead.Joined -> identified(read.membership, version, id)
        is MembershipRead.NotJoined -> identified(null, version, id)
        is MembershipRead.Unavailable -> unreadable(listOfNotNull("config: ${read.detail}", identityFailure(id)))
    }

    private fun identified(membership: Membership?, version: Long, id: DeviceIdRead): GateRead = when (id) {
        is DeviceIdRead.Ok -> GateRead.Read(membership, version)
        DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> unreadable(listOfNotNull(identityFailure(id)))
    }

    private fun unreadable(failures: List<String>): GateRead =
        GateRead.Unreadable("protected data unavailable (${failures.joinToString(", ")})")

    private fun held(read: GateRead): Membership? = when (read) {
        is GateRead.Read -> read.membership
        is GateRead.Unreadable -> null
    }

    private fun membershipFailure(read: MembershipRead): String? = when (read) {
        is MembershipRead.Joined, is MembershipRead.NotJoined -> null
        is MembershipRead.Unavailable -> "config: ${read.detail}"
    }

    /**
     * Naming WHICH identity failure occurred is the difference between "the device is locked, this will pass" and "this
     * process has no identity and may not create one", which need opposite reactions from whoever reads the log.
     */
    private fun identityFailure(read: DeviceIdRead): String? = when (read) {
        is DeviceIdRead.Ok -> null
        DeviceIdRead.Absent -> "deviceId absent and unmintable here"
        is DeviceIdRead.Unavailable -> "deviceId unreadable (${read.detail})"
    }
}

/** An entry's reads: what it [read], and the joined event's key [presence]. */
class PassEntry(val read: GateRead, val presence: KeyPresence)
