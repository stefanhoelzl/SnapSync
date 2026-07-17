package app.snapsync.ios.discovery

import app.snapsync.engine.DISCOVERY_TOKEN_KEY
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.ports.DiscoveryStore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.posix.memcpy

/**
 * The App-Group-backed [DiscoveryStore]: the archived `PHPersistentChangeToken` bytes live in the
 * shared `NSUserDefaults` suite so the cursor survives process death. [IosDiscovery] archives the
 * token to/from these bytes; this only persists them. Shared by both upload tiers.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDiscoveryStore(
    suiteName: String = LEDGER_APP_GROUP,
) : DiscoveryStore {

    private val defaults = NSUserDefaults(suiteName = suiteName)

    // Best-effort: a defaults failure must not fail the cycle (degrades to full re-enumeration).
    override fun loadToken(): ByteArray? =
        runCatching { defaults.dataForKey(DISCOVERY_TOKEN_KEY)?.toByteArray() }.getOrNull()

    override fun saveToken(token: ByteArray) {
        runCatching { defaults.setObject(token.toNSData(), forKey = DISCOVERY_TOKEN_KEY) }
    }

    override fun clearToken() {
        runCatching { defaults.removeObjectForKey(DISCOVERY_TOKEN_KEY) }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
