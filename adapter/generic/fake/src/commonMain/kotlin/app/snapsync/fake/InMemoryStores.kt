package app.snapsync.fake

import app.snapsync.model.DeviceManifestAsset
import app.snapsync.ports.AlbumMapStore
import app.snapsync.ports.DeviceManifestStore

/** The honest in-memory [DeviceManifestStore] for the composed `DeviceManifestProducer`. */
internal class InMemoryDeviceManifestStore : DeviceManifestStore {
    private var lastUploaded: String? = null

    override fun loadLastUploaded(): String? = lastUploaded
    override fun saveLastUploaded(json: String) {
        lastUploaded = json
    }
    override fun clearLastUploaded() {
        lastUploaded = null
    }
}

/** The honest in-memory [AlbumMapStore] — the leave-surviving `eventId → albumLocalId` map. */
internal class InMemoryAlbumMapStore(initial: Map<String, String> = emptyMap()) : AlbumMapStore {
    private val map = initial.toMutableMap()
    override fun get(eventId: String): String? = map[eventId]
    override fun put(eventId: String, albumLocalId: String) {
        map[eventId] = albumLocalId
    }
}
