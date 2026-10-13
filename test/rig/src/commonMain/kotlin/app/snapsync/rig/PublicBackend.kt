package app.snapsync.rig

import app.snapsync.contracts.PhotoLibrary
import app.snapsync.domain.device.DeviceId
import app.snapsync.levers.EventRecord
import app.snapsync.levers.PhotoKind
import app.snapsync.levers.PublicBackendLevers
import app.snapsync.levers.UnionEntry
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset

/**
 * [PublicBackendLevers] over [reach] — the backend's public surface, as another member or the operator's seeding reaches
 * it — so every method holds against the real `api/` as well as the mock. An omitted `event` or `device` the interface
 * marks arrives here already resolved by the channel.
 */
internal class PublicBackend(private val world: MockWorld, private val reach: BackendReach) : PublicBackendLevers {
    override suspend fun union(event: String?): List<UnionEntry> = reach.unionOf(named(event)).map { a ->
        UnionEntry(a.deviceId.value, a.assetId.value, a.resources.map { it.role })
    }

    override suspend fun event(event: String?): EventRecord {
        val id = named(event)
        val (registered, name) = reach.eventOf(id)
        return EventRecord(registered, name, reach.closedAtOf(id))
    }

    override suspend fun objects(device: String?, event: String?): List<String> =
        reach.objectsOf(named(event), named(device)).sorted()

    override suspend fun foreignDevice(
        device: String,
        assets: List<String>,
        event: String?,
        kind: PhotoKind?,
        filename: String?,
        final: Boolean?,
        uploading: List<String>?,
    ): String {
        require(assets.isNotEmpty()) { "assets must name at least one asset" }
        val motion = kind == PhotoKind.MOTION_PHOTO
        // The capturing device's own file name for every asset — what an import names its photo after.
        val name = filename ?: if (motion) MOTION_FILENAME else null
        val manifest = assets.map {
            when {
                motion -> foreignAsset(it, name!!, contentType = "image/jpeg")
                name != null -> foreignAsset(it, name)
                else -> foreignAsset(it)
            }
        }
        val bytes = if (motion) PhotoLibrary.motionPhoto else null
        return addForeignDevice(
            device,
            manifest,
            event ?: world.joinedEventId(),
            bytes,
            final == true,
            uploading.orEmpty().toSet(),
        )
    }

    override suspend fun foreignDeviceLeave(device: String, event: String?) {
        reach.leave(named(event), device)
    }

    override suspend fun foreignDeviceReceived(device: String, event: String?) {
        reach.reportReceived(named(event), device)
    }

    /**
     * A fellow member through the backend's public surface: [who] joins [event] — or an event the backend mints for it —
     * uploads every resource's bytes where the app's uploader addresses them — but [uploading]'s, still on its phone —
     * and publishes its manifest. Returns the event.
     */
    private suspend fun addForeignDevice(
        who: String,
        assets: List<DeviceManifestAsset>,
        event: String?,
        bytes: ByteArray?,
        final: Boolean,
        uploading: Set<String>,
    ): String {
        val eventId = event ?: reach.createEvent(FOREIGN_EVENT_NAME, FOREIGN_EVENT_START)
        reach.join(eventId, who)
        assets.filterNot { it.assetId.value in uploading }.forEach { asset ->
            asset.resources.forEach {
                if (bytes != null) {
                    reach.upload(
                        who,
                        asset.assetId,
                        it,
                        bytes,
                        eventId,
                    )
                } else {
                    reach.upload(who, asset.assetId, it, eventId = eventId)
                }
            }
        }
        val device =
            requireNotNull(DeviceId.parse(who)) { "device must be a device id — a canonical UUID — was '$who'" }
        reach.publish(eventId, DeviceManifest(deviceId = device, assets = assets, final = final))
        return eventId
    }

    private fun named(id: String?): String = requireNotNull(id) { "the channel names the joined event and this device" }

    private companion object {
        const val MOTION_FILENAME = "PXL_MOTION.MP.jpg"
        const val FOREIGN_EVENT_NAME = "Anna's Birthday"
        const val FOREIGN_EVENT_START = "2026-01-01T00:00:00Z"
    }
}
