package app.snapsync.data.upload

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
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
 */
class DeviceListing(private val gateway: BackendGateway, private val identity: DeviceIdentity) {

    /** The resources the backend stores for this device in [event]. */
    suspend fun stored(event: EventId): ListingRead = when (val id = identity.id()) {
        is DeviceIdRead.Ok -> listed(event, id.value)
        DeviceIdRead.Absent -> ListingRead.Unavailable("no device id is held here")
        is DeviceIdRead.Unavailable -> ListingRead.Unavailable(id.detail)
    }

    private suspend fun listed(event: EventId, device: DeviceId): ListingRead =
        when (val reply = gateway.send(Routes.DeviceFiles.request(event.value, device.value))) {
            is DeviceFilesReply.Listed -> listedOf(reply.items.map(::stored))
            // An answer that does not decode at all — a captive portal's page — is no answer, and heals.
            is AppTooOld, is Invalid, is Maintenance, is Malformed, NotThisDevice, is ServerError, Unauthorized,
            is Unreachable,
            -> ListingRead.Unavailable("$reply")
        }

    /**
     * A listing that decodes but names an asset this build cannot read will not heal, unlike the network: its own
     * case, so it is told louder — and none of it is seeded, rather than a part.
     */
    private fun listedOf(items: List<StoredBytes?>): ListingRead = when (items.all { it != null }) {
        true -> ListingRead.Listed(items.filterNotNull())
        false -> ListingRead.Unreadable("${items.count { it == null }} listed resource(s) name no readable asset")
    }

    @OptIn(MintsStoredBytes::class)
    private fun stored(item: StoredResource): StoredBytes? =
        AssetId.parse(item.assetId)?.let { StoredBytes(UploadKey.of(it, roleOf(item.role), item.filename)) }

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
