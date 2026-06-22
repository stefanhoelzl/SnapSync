package app.snapsync.ios.upload

/**
 * The persisted device id — a canonical UUID that scopes this device's uploads within an event (the
 * `<deviceId>` path segment of the edge URL). A `commonMain` port over opaque storage so the
 * read-or-mint orchestration ([DeviceIdProvider]) is testable with a fake; the iOS impl
 * ([IosDeviceIdStore]) persists it in the shared App-Group `NSUserDefaults`.
 */
interface DeviceIdStore {
    fun load(): String?
    fun save(id: String)
}

/**
 * Read-or-mint the device id: returns the persisted value, or mints one via [mint] (a fresh UUID),
 * persists it, and returns it. Stable for the install — minted once on first need and reused across
 * cycles and process restarts; it rotates only when the App Group is wiped (uninstall). A
 * re-provision does not touch it.
 *
 * [mint] is injected (the iOS root supplies `NSUUID`) so this orchestration stays platform-free and
 * runs on the simulator with a fake store + deterministic mint.
 */
class DeviceIdProvider(
    private val store: DeviceIdStore,
    private val mint: () -> String,
) {
    fun deviceId(): String = store.load() ?: mint().also(store::save)
}
