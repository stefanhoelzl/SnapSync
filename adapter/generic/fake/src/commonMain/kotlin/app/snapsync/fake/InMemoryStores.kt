package app.snapsync.fake

import app.snapsync.model.DeviceManifestAsset
import app.snapsync.ports.AlbumMapStore
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.JoinedEventMarker

/** An honest in-memory [JoinedEventMarker] for the composed `UploadReconciler`. */
internal class InMemoryJoinedEventMarker(private var value: String? = null) : JoinedEventMarker {
    override fun read(): String? = value
    override fun set(eventId: String) {
        value = eventId
    }
    override fun clear() {
        value = null
    }
}

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
