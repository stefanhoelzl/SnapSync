package app.snapsync.fake

import app.snapsync.ports.AlbumMapStore
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.PushRegistrationRecord

/** The honest in-memory [DeviceManifestStore] for the composed `DeviceManifestProducer`. */
internal class InMemoryDeviceManifestStore(private var lastUploaded: String? = null) : DeviceManifestStore {

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

/** The honest in-memory [PushRegistrationRecord] — the last push registration the backend accepted. */
internal class InMemoryPushRegistrationRecord(private var lastRegistered: String? = null) : PushRegistrationRecord {
    override fun loadLastRegistered(): String? = lastRegistered
    override fun saveLastRegistered(value: String) {
        lastRegistered = value
    }
}
