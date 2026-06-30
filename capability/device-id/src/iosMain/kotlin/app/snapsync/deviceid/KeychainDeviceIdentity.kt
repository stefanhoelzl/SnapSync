@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.deviceid

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * The iOS [DeviceIdentity]: persists the device id as a single Keychain generic-password item
 * (encrypted at rest, survives app updates, process death, **and reinstall**). On first resolution it
 * mints an `NSUUID` and writes it; thereafter it reads the same value back. The value is the UUID's
 * UTF-8 bytes.
 *
 * No `kSecAttrAccessGroup` is set: the app's `keychain-access-groups` entitlement declares the shared
 * group as its first entry, so the item lands in that shared group by default and the upload
 * extension (declaring the same entitlement) reads the **same** id — sharing is purely an entitlement
 * concern, no team-id prefix hardcoded. Mirrors `KeychainConfigStore`.
 *
 * The resolved id is cached for the process lifetime (one read/mint per process).
 */
class KeychainDeviceIdentity(
    private val service: String = "app.snapsync.deviceid",
    private val account: String = "deviceid",
) : DeviceIdentity {

    private val cached: String by lazy {
        resolveDeviceId(
            read = ::readValue,
            write = ::writeValue,
            generate = { NSUUID().UUIDString() },
        )
    }

    override fun deviceId(): String = cached

    private fun readValue(): String? = memScoped {
        val query = baseQuery()
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return@memScoped null
        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        data.toByteArray().decodeToString()
    }

    private fun writeValue(value: String) {
        // Replace-by-delete-then-add keeps the write idempotent regardless of prior presence.
        deleteItem()
        val addQuery = baseQuery()
        val cfData = CFBridgingRetain(value.encodeToByteArray().toNSData())
        CFDictionaryAddValue(addQuery, kSecValueData, cfData)
        val status = SecItemAdd(addQuery, null)
        CFRelease(addQuery)
        CFBridgingRelease(cfData)
        check(status == errSecSuccess) { "keychain add failed: $status" }
    }

    private fun deleteItem() {
        val deleteQuery = baseQuery()
        SecItemDelete(deleteQuery)
        CFRelease(deleteQuery)
    }

    private fun baseQuery(): CFMutableDictionaryRef {
        val dict = CFDictionaryCreateMutable(
            null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: error("could not allocate keychain query")
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, cfString(service))
        CFDictionaryAddValue(dict, kSecAttrAccount, cfString(account))
        return dict
    }

    private fun cfString(value: String) =
        CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
    }

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply { usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
