package app.snapsync.data.upload

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.crypto.NameReading
import app.snapsync.data.crypto.ServedNames
import app.snapsync.data.identity.AppDeviceIdentity
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.receiving.ServedFileName
import app.snapsync.domain.upload.MintsStoredBytes
import app.snapsync.domain.upload.StoredBytes
import app.snapsync.domain.upload.UploadKey
import app.snapsync.wire.api.AppTooOld
import app.snapsync.wire.api.DeviceFilesReply
import app.snapsync.wire.api.Invalid
import app.snapsync.wire.api.Maintenance
import app.snapsync.wire.api.Malformed
import app.snapsync.wire.api.NotThisDevice
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerError
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.api.Unreachable
import app.snapsync.wire.models.Role
import app.snapsync.wire.models.StoredResource

/**
 * **What the backend stores for this device in an event** — the one party that knows when bytes landed long before
 * the OS acknowledged their job. Each listed resource is the evidence its in-flight row may be settled
 * ([StoredBytes]); an item whose asset id is not canonical is not this device's resource, and is left out.
 *
 * Each item is keyed as this device keyed its own upload: from the name it uploaded under, which an encrypted event
 * stores only sealed — so it is read through [names] (decision record `changes/seal-file-names`, D6). An item whose
 * sealed name does not open is no evidence of anything: it is left out, and the upload it stands for is sent again,
 * overwriting it.
 */
class DeviceListing(
    private val gateway: BackendGateway,
    private val identity: AppDeviceIdentity,
    private val names: ServedNames,
) {

    /** The resources the backend stores for this device in [membership]'s event. */
    suspend fun stored(membership: Membership): ListingRead = when (val id = identity.id()) {
        is DeviceIdRead.Ok -> named(membership, id.value)
        is DeviceIdRead.Unavailable -> ListingRead.Unavailable(id.detail)
    }

    private suspend fun named(membership: Membership, device: DeviceId): ListingRead {
        val event = membership.event.id
        return when (val reading = names.of(membership)) {
            is NameReading.Opens -> listed(Listing(event, device, reading))
            is NameReading.Unavailable -> ListingRead.Unavailable(
                "the names of $event cannot be read: ${reading.detail}",
            )
        }
    }

    private suspend fun listed(listing: Listing): ListingRead =
        when (val reply = gateway.send(Routes.DeviceFiles.request(listing.event.value, listing.device.value))) {
            is DeviceFilesReply.Listed -> listedOf(reply.items.map { stored(listing, it) })
            // An answer that does not decode at all — a captive portal's page — is no answer, and heals.
            is AppTooOld, is Invalid, is Maintenance, is Malformed, NotThisDevice, is ServerError, Unauthorized,
            is Unreachable,
            -> ListingRead.Unavailable("$reply")
        }

    /**
     * A listing that decodes but names an asset this build cannot read will not heal, unlike the network: its own
     * case, so it is told louder — and none of it is seeded, rather than a part.
     */
    private fun listedOf(items: List<ItemRead>): ListingRead = when (items.none { it is ItemRead.Unreadable }) {
        true -> ListingRead.Listed(items.mapNotNull { (it as? ItemRead.Stored)?.bytes })
        false -> ListingRead.Unreadable(
            "${items.count { it is ItemRead.Unreadable }} listed resource(s) name no readable asset",
        )
    }

    private fun stored(listing: Listing, item: StoredResource): ItemRead =
        when (val asset = AssetId.parse(item.assetId)) {
            null -> ItemRead.Unreadable
            is AssetId -> stored(listing, asset, roleOf(item.role), item.filename)
        }

    @OptIn(MintsStoredBytes::class)
    private fun stored(listing: Listing, asset: AssetId, role: ResourceRole, served: String): ItemRead =
        when (val name = listing.reading.nameOf(FileBinding(listing.event, listing.device, asset, role), served)) {
            is ServedFileName.Named -> ItemRead.Stored(StoredBytes(UploadKey.of(asset, role, name.name.value)))
            is ServedFileName.Unopened -> ItemRead.Unopened
            // A plain event's empty name keys its item as it always did: a `.bin`.
            ServedFileName.Malformed -> ItemRead.Stored(StoredBytes(UploadKey.of(asset, role, served)))
        }

    /** One listing being read: this [device]'s resources in [event], and how their names are read. */
    private class Listing(val event: EventId, val device: DeviceId, val reading: NameReading.Opens)

    private sealed interface ItemRead {
        class Stored(val bytes: StoredBytes) : ItemRead

        /** A sealed name that does not open: no evidence, left out. */
        data object Unopened : ItemRead

        /** An asset id this build cannot read. */
        data object Unreadable : ItemRead
    }

    private fun roleOf(role: Role): ResourceRole = when (role) {
        Role.PRIMARY -> ResourceRole.PRIMARY
        Role.LIVE -> ResourceRole.LIVE
    }
}

/** What the backend stores for this device in an event, or that it could not be asked or understood. */
sealed interface ListingRead {
    class Listed(val stored: List<StoredBytes>) : ListingRead

    /** It could not be asked now, or was refused. [detail] is diagnostics only. */
    data class Unavailable(val detail: String) : ListingRead

    /** It answered something this build cannot read — which will not heal by itself. */
    data class Unreadable(val detail: String) : ListingRead
}
