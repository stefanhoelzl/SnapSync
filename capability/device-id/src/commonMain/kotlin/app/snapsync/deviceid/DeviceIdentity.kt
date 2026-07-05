package app.snapsync.deviceid

/**
 * The stable per-install device identity (capability `device-identity`). [deviceId] is a UUID minted
 * **once** and persisted, identical across the app and the upload extension (one shared Keychain
 * item), and **stable across reinstall** (the Keychain survives app uninstall). It is the
 * `/files/devices/<deviceId>/` byte-store partition and the per-event device-manifest key
 * (`/events/<eventId>/devices/<deviceId>.json`) — the "which manifest is mine" handle a future
 * restore needs to tell this device's own (possibly-deleted) photos from another contributor's.
 *
 * Resolution is synchronous (like the config seam): the value is available the moment a backing
 * store is constructed.
 */
interface DeviceIdentity {
    fun deviceId(): String
}

/**
 * The mint-once-then-read core, shared by every backing store and tested in `commonTest` (so it runs
 * on JVM **and** the iOS simulator): return the persisted id when [read] yields one; otherwise
 * [generate] a fresh id, [write] it, and return it. Pure — the platform supplies read/write/generate
 * (iOS: a Keychain item + `NSUUID`). Idempotent: once written, every later resolution reads it back
 * and never generates again.
 */
fun resolveDeviceId(read: () -> String?, write: (String) -> Unit, generate: () -> String): String =
    read() ?: generate().also(write)

/** A fixed identity for tests and the desktop harness (no platform store). */
class FixedDeviceIdentity(private val id: String) : DeviceIdentity {
    override fun deviceId(): String = id
}
