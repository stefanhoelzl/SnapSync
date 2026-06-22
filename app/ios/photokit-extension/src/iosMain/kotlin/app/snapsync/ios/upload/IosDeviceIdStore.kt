package app.snapsync.ios.upload

import app.snapsync.engine.LEDGER_APP_GROUP
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults

/** The App-Group `NSUserDefaults` key the device id is persisted under (shared app/extension suite). */
const val DEVICE_ID_KEY: String = "device.id"

/**
 * The App-Group-backed [DeviceIdStore]: the device id lives in the shared `NSUserDefaults` suite so
 * it survives extension process death and is the same value across cycles. Best-effort — a defaults
 * read failure degrades to minting a fresh id (the cycle is never failed by it).
 */
class IosDeviceIdStore(
    suiteName: String = LEDGER_APP_GROUP,
) : DeviceIdStore {

    private val defaults = NSUserDefaults(suiteName = suiteName)

    override fun load(): String? =
        runCatching { defaults.stringForKey(DEVICE_ID_KEY) }.getOrNull()

    override fun save(id: String) {
        runCatching { defaults.setObject(id, forKey = DEVICE_ID_KEY) }
    }
}

/**
 * The iOS [DeviceIdProvider]: persists in the App Group and mints with Foundation `NSUUID` (a
 * lowercase canonical UUID) — **not** UIKit `identifierForVendor`, so it is available even in a
 * background-launched, locked-device extension and is never `nil`.
 */
fun iosDeviceIdProvider(): DeviceIdProvider =
    DeviceIdProvider(IosDeviceIdStore()) { NSUUID().UUIDString().lowercase() }
