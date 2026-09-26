package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.ResourceRole
import app.snapsync.model.Reply
import app.snapsync.ports.Backend
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [BackendSetup] for a backend with no HTTP surface — the in-memory mock: every step goes through the [backend]
 * port itself, which IS that backend's public surface, and bytes land in [storedFiles], the cell the mock was given
 * in place of an uploader (the byte upload is the OS transfer's route, not the backend port's).
 */
class PortSetup(
    private val backend: Backend,
    private val storedFiles: MutableMap<String, MutableSet<DeviceFile>>,
) : BackendSetup {

    @OptIn(ExperimentalUuidApi::class)
    override fun freshId(): String = Uuid.random().toString()

    override suspend fun createEvent(name: String): CreatedEvent {
        val created = checked("create event", backend.createEvent(null, CreateEventRequest(name, SEEDED_STARTS_AT, SEEDED_ENDS_AT)))
        return CreatedEvent(created.eventId, created.name ?: name, createdAt = "")
    }

    override suspend fun join(eventId: String, deviceId: String) {
        checked("join $deviceId", backend.joinEvent(null, eventId, deviceId))
    }

    override suspend fun fillToCapacity(eventId: String) {
        repeat(MAX_CAPACITY_PROBE) {
            val reply = backend.joinEvent(null, eventId, freshId())
            if (reply is Reply.Refused && reply.status == CONFLICT) return
            checked("fill to capacity", reply)
        }
        error("setup step 'fill to capacity': still admitting after $MAX_CAPACITY_PROBE joins")
    }

    override suspend fun publish(eventId: String, deviceId: String, assets: List<SeededAsset>) {
        checked("publish manifest", backend.publishManifest(null, eventId, deviceId, DeviceManifest(deviceId, assets.map { it.manifestEntry() })))
    }

    override suspend fun unionAssetIds(eventId: String): Set<AssetId> =
        checked("read the union", backend.eventFiles(eventId)).mapTo(mutableSetOf()) { it.assetId }

    override suspend fun upload(deviceId: String, asset: SeededAsset, role: ResourceRole) {
        storedFiles.getOrPut(deviceId) { mutableSetOf() } += DeviceFile(asset.assetId, role, asset.filename)
    }

    private fun <T> checked(step: String, reply: Reply<T>): T =
        (reply as? Reply.Ok)?.value ?: error("setup step '$step' was refused by the backend: $reply")

    private companion object {
        const val MAX_CAPACITY_PROBE = 200
        const val CONFLICT = 409
    }
}
