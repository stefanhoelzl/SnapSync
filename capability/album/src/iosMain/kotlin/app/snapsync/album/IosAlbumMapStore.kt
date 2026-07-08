@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.album

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
 * The iOS [AlbumMapStore] (capability `event-album`): the `eventId → albumLocalId` map, persisted as a
 * single JSON Keychain generic-password item in the **shared** keychain-access-group (no
 * `kSecAttrAccessGroup` set — it inherits the app's first/shared group, exactly like `KeychainConfigStore`),
 * so both the app (which writes on album creation) and the upload extension (which reads on placement) see
 * it. It uses a **distinct** service/account from the config item, so `LeaveEvent.leave()`'s config
 * `clear()` never touches it — the map **survives leave**, which is what lets a re-join reuse the same
 * album. Reads hit the Keychain directly each call (no cached `StateFlow`), so a cross-process reader is
 * always current.
 */
class IosAlbumMapStore(
    private val service: String = "app.snapsync.album",
    private val account: String = "albummap",
) : AlbumMapStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    override fun get(eventId: String): String? = readMap()[eventId]

    override fun put(eventId: String, albumLocalId: String) {
        val updated = readMap().toMutableMap().apply { this[eventId] = albumLocalId }
        writeValue(json.encodeToString(serializer, updated))
    }

    private fun readMap(): Map<String, String> {
        val stored = readValue() ?: return emptyMap()
        return runCatching { json.decodeFromString(serializer, stored) }.getOrDefault(emptyMap())
    }

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
