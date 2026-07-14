@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.keychain

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
import platform.CoreFoundation.CFRetain
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
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * The accessibility class every SnapSync Keychain item is stored under: readable while the device is
 * **locked**, once it has been unlocked at least once since boot.
 *
 * This is the whole fix. The iOS default is `kSecAttrAccessibleWhenUnlocked`, under which *every*
 * background wake — a `BGProcessingTask`, a silent push, a background `URLSession` completion, and the
 * OS-scheduled upload extension — fails to read the device id and the event config, because background
 * work runs when the device is *idle*, which usually means *locked*.
 *
 * It is deliberately **not** `…ThisDeviceOnly`: the item must ride an encrypted backup, because the app
 * container (the SQL ledger and the discovery cursor) rides it too. A device-bound id would give a
 * restored phone a *fresh* identity alongside a *restored* ledger that claims everything is already
 * uploaded — so it would upload nothing while its manifest sat empty. Keeping the id restorable keeps
 * id ↔ ledger ↔ partition consistent. Decision record: `changes/archive/…-fix-locked-device-keychain-access`.
 */
val ACCESSIBLE_AFTER_FIRST_UNLOCK: String =
    CFBridgingRelease(CFRetain(kSecAttrAccessibleAfterFirstUnlock)) as String

/**
 * The one and only Keychain implementation in the repo (capability `architecture-guards` forbids
 * `SecItem*` outside this module, so that "every Keychain item is background-readable" is provable
 * rather than merely intended).
 *
 * No `kSecAttrAccessGroup` is set: the app's `keychain-access-groups` entitlement declares the shared
 * group as its first entry, so items land in that shared group by default and the upload extension —
 * declaring the same entitlement — reads the same item. Sharing is purely an entitlement concern; no
 * team-id prefix is hardcoded.
 */
class IosKeychain(
    private val service: String,
    private val account: String,
) : Keychain {

    /**
     * One query returns **both** the value and its accessibility class, so detecting a legacy item
     * costs nothing: an already-correct item is read, compared, and left alone (no write).
     */
    override fun read(): KeychainRead = memScoped {
        val query = baseQuery()
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecReturnAttributes, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)

        // The load-bearing distinction: ONLY item-not-found means "there is no value". Every other
        // status means "I could not look" — most often errSecInteractionNotAllowed (-25308) on a
        // locked device — and must never be mistaken for absence.
        if (status == errSecItemNotFound) return@memScoped KeychainRead.Absent
        if (status != errSecSuccess) return@memScoped KeychainRead.Unavailable(status)

        // `SecItemCopyMatching` returns a +1 dictionary; `CFBridgingRelease` takes that ownership over
        // and bridges it to a Kotlin Map, so the keys arrive as the plain strings the CF constants
        // bridge to. No manual CFRelease of the result, and no reinterpret.
        @Suppress("UNCHECKED_CAST")
        val attributes = CFBridgingRelease(result.value) as? Map<String, Any?>
        val value = (attributes?.get(KEY_VALUE_DATA) as? NSData)?.toByteArray()?.decodeToString()
        val accessibility = attributes?.get(KEY_ACCESSIBLE) as? String

        // A matched item whose data will not decode is not an absence either — refuse to mint over it.
        value?.let { KeychainRead.Found(it, accessibility) } ?: KeychainRead.Unavailable(errSecSuccess)
    }

    /**
     * The attributes **every** written item carries, as plain strings. This is the single source of
     * truth: both [write] and [migrateAccessibility] build their CF dictionaries from it, so a test can
     * assert the accessibility class the adapter really applies without needing a working Keychain —
     * which, as it turns out, no test has: a Kotlin/Native test binary is not an app bundle, so
     * `securityd` refuses it Keychain access outright (`errSecNotAvailable`, -25291).
     *
     * That makes this the *only* mechanical proof that every item is written background-readable — the
     * half of capability `architecture-guards`'s two-part argument that containment alone cannot supply.
     */
    internal fun writtenAttributes(): Map<String, String> =
        mapOf(KEY_ACCESSIBLE to ACCESSIBLE_AFTER_FIRST_UNLOCK)

    /** Apply [writtenAttributes] to a CF dictionary. Keys and values are compared by value by Security. */
    private fun applyWrittenAttributes(dict: CFMutableDictionaryRef) {
        writtenAttributes().forEach { (key, value) ->
            CFDictionaryAddValue(dict, cfString(key), cfString(value))
        }
    }

    override fun write(value: String) {
        // Replace-by-delete-then-add keeps the write idempotent regardless of prior presence.
        delete()
        val addQuery = baseQuery()
        val cfData = CFBridgingRetain(value.encodeToByteArray().toNSData())
        CFDictionaryAddValue(addQuery, kSecValueData, cfData)
        applyWrittenAttributes(addQuery)
        val status = SecItemAdd(addQuery, null)
        CFRelease(addQuery)
        CFBridgingRelease(cfData)
        if (status != errSecSuccess) throw KeychainUnavailable(status)
    }

    /**
     * `SecItemUpdate` changes the item's accessibility class and **nothing else** — the value is not
     * supplied, so it cannot be altered. This is what lets an already-provisioned device heal without
     * its device id changing (a new id would orphan its byte partition and its ledger).
     */
    override fun migrateAccessibility() {
        val query = baseQuery()
        val attributes = newDictionary()
        applyWrittenAttributes(attributes)
        val status = SecItemUpdate(query, attributes)
        CFRelease(query)
        CFRelease(attributes)
        // Best-effort: a device that cannot be migrated right now keeps its (readable) item and retries
        // on the next read. Failing the read here would turn a healthy legacy device into a broken one.
        if (status != errSecSuccess) {
            log.w { "keychain accessibility migration failed for $service/$account: status=$status" }
        }
    }

    override fun delete() {
        val deleteQuery = baseQuery()
        SecItemDelete(deleteQuery)
        CFRelease(deleteQuery)
    }

    private fun baseQuery(): CFMutableDictionaryRef {
        val dict = newDictionary()
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, cfString(service))
        CFDictionaryAddValue(dict, kSecAttrAccount, cfString(account))
        return dict
    }

    private fun newDictionary(): CFMutableDictionaryRef = CFDictionaryCreateMutable(
        null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    ) ?: error("could not allocate keychain query")

    private fun cfString(value: String) = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)

    private companion object {
        val log = co.touchlab.kermit.Logger.withTag("Keychain")

        /** The plain strings the CF attribute-key constants bridge to (`"v_Data"`, `"pdmn"`) — derived
         *  from the constants themselves rather than hardcoded. */
        val KEY_VALUE_DATA: String = CFBridgingRelease(CFRetain(kSecValueData)) as String
        val KEY_ACCESSIBLE: String = CFBridgingRelease(CFRetain(kSecAttrAccessible)) as String
    }
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
