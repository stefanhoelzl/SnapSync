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
 *
 * **Resolution can fail, and the failure is not "absent".** The backing store is the Keychain, which
 * is unreadable while protected data is unavailable (before the first unlock after a reboot). That
 * case raises `app.snapsync.keychain.KeychainUnavailable`: it does **not** mint a new id, and does not
 * return a placeholder. The composition roots catch it — the app defers its background work until
 * protected data becomes available, and the extension skips its cycle. The read/mint/migrate decision
 * lives in `:domain:keychain` (`resolveOrMint`), which is where it is tested.
 *
 * There is deliberately **no** `resolveDeviceId(read, write, generate)` helper here any more. Its
 * two-state contract — `read()` returning `null` to mean "no id stored" — *was* the bug: it cannot
 * express "I could not look", so every read failure became a mint, and a mint on a locked device
 * aborted the process (or, worse, would have silently given the device a new identity). Decision
 * record: `changes/archive/…-fix-locked-device-keychain-access`.
 */
interface DeviceIdentity {
    fun deviceId(): String
}

/** A fixed identity for tests and the desktop harness (no platform store). */
class FixedDeviceIdentity(private val id: String) : DeviceIdentity {
    override fun deviceId(): String = id
}
