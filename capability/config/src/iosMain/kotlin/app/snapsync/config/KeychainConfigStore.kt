@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.config

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * The iOS [ConfigSource]/[ConfigStore]: persists the config as a single Keychain generic-password
 * item (encrypted at rest, survives app updates and process death). The value stored is the
 * canonical deeplink URL's UTF-8 bytes — reusing the one codec, so persistence rides the same
 * format as the wire.
 *
 * No `kSecAttrAccessGroup` is set: with the app's `keychain-access-groups` entitlement declaring the
 * shared group as its (only/first) entry, items land in that shared group by default, so the future
 * background upload extension — declaring the same entitlement — reads the same item. This avoids
 * hardcoding the team-id prefix in code; sharing is purely an entitlement concern.
 *
 * Seeds [config] synchronously at construction, mirroring the permission adapter's synchronous-real
 * guarantee.
 */
class KeychainConfigStore(
    private val service: String = "app.snapsync.config",
    private val account: String = "eventconfig",
) : ConfigSource, ConfigStore {

    private val state = MutableStateFlow(readConfig())
    override val config: StateFlow<EventConfigPayload?> = state

    override suspend fun save(config: EventConfigPayload) {
        // Idempotent: re-scanning the same config is a no-op; a different one replaces silently.
        if (state.value?.sameAs(config) == true) return
        writeUrl(encodeConfigUrl(config))
        state.value = config
    }

    override suspend fun clear() {
        // Idempotent: deleting an absent item is treated as success (the leave path tolerates it).
        deleteItem()
        state.value = null
    }

    private fun readConfig(): EventConfigPayload? {
        val url = readUrl() ?: return null
        return (decodeConfigUrl(url) as? ConfigDecodeResult.Success)?.payload
    }

    private fun readUrl(): String? = memScoped {
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

    private fun writeUrl(url: String) {
        // Replace-by-delete-then-add keeps the write idempotent regardless of prior presence.
        deleteItem()

        val addQuery = baseQuery()
        val cfData = CFBridgingRetain(url.encodeToByteArray().toNSData())
        CFDictionaryAddValue(addQuery, kSecValueData, cfData)
        val status = SecItemAdd(addQuery, null)
        CFRelease(addQuery)
        CFBridgingRelease(cfData)
        check(status == errSecSuccess) { "keychain add failed: $status" }
    }

    private fun deleteItem() {
        // Deleting an absent item returns errSecItemNotFound, which we tolerate (idempotent).
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
