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
            is DeviceFilesReply.Listed -> ListingRead.Listed(reply.items.mapNotNull(::stored))
            is AppTooOld, is Invalid, is Maintenance, is Malformed, NotThisDevice, is ServerError, Unauthorized,
            is Unreachable,
            -> ListingRead.Unavailable("$reply")
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

    data class Unavailable(val detail: String) : ListingRead
}
