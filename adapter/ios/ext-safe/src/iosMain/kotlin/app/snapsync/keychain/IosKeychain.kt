@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.keychain

import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.SecureStoreUnavailable
import app.snapsync.ports.StoredProtection

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
import platform.Security.kSecAttrAccessGroup
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
 * The one and only Keychain implementation in the repo — the iOS binding of the platform-free
 * [SecureStore] port (capability `architecture-guards` forbids `SecItem*` outside this module, so
 * that "every Keychain item is background-readable" is provable rather than merely intended).
 *
 * **This class owns both platform encodings the port refuses to carry.** An `OSStatus` becomes an
 * opaque diagnostic string, and `kSecAttrAccessible`'s value becomes a [StoredProtection] — resolved
 * against [ACCESSIBLE_AFTER_FIRST_UNLOCK], which is the single place the required class is named.
 * Decision record: `changes/…/reshape-keychain-port` (D3, D4).
 *
 * [accessGroup] names the Keychain access group **explicitly**, and every operation this class
 * performs carries it — read, write, delete, and the accessibility migration alike. A partially
 * scoped item is worse than an unscoped one: it would be written to one group and searched for in
 * another.
 *
 * It is a parameter, and `null` (search and write wherever the platform decides) is still the
 * default, because exactly one item legitimately wants that: the legacy config reader, whose whole
 * job is to find an item an older build left *anywhere*. Everything else names its group.
 *
 * This used to read: *"No `kSecAttrAccessGroup` is set: the app's entitlement declares the shared
 * group as its first entry, so items land there by default and the upload extension reads the same
 * item."* That was false, and the device proved it. When no group is named the platform chooses one
 * **at write time** from the entitlements of the build performing the write, so an item's group is a
 * fact about its author, not about this contract. A build signed through the dev re-sign inherits the
 * provisioning profile's wildcard grant (`<team>.*` — every Apple *development* profile grants it,
 * because keychain groups need no portal registration and Apple cannot know which concrete groups are
 * intended); a wildcard is not a writable group name, so writes fall back to each process's own
 * `application-identifier` group. On 2026-07-20 the app and the upload extension therefore held two
 * *different* device-id items, in two groups neither could see into, while **both reads reported
 * success** — the app re-downloaded and re-imported every photo it had itself uploaded.
 *
 * Decision record: `changes/archive/2026-07-20-fix-split-device-identity`.
 */
class IosKeychain(
    private val service: String,
    private val account: String,
    private val accessGroup: String? = null,
) : SecureStore {

    /**
     * One query returns **both** the value and its accessibility class, so detecting a legacy item
     * costs nothing: an already-correct item is read, compared, and left alone (no write).
     */
    override fun read(): SecureStoreRead = memScoped {
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
        if (status == errSecItemNotFound) return@memScoped SecureStoreRead.Absent
        if (status != errSecSuccess) return@memScoped SecureStoreRead.Unavailable(diagnostic(status))

        // `SecItemCopyMatching` returns a +1 dictionary; `CFBridgingRelease` takes that ownership over
        // and bridges it to a Kotlin Map, so the keys arrive as the plain strings the CF constants
        // bridge to. No manual CFRelease of the result, and no reinterpret.
        @Suppress("UNCHECKED_CAST")
        val attributes = CFBridgingRelease(result.value) as? Map<String, Any?>
        val value = (attributes?.get(KEY_VALUE_DATA) as? NSData)?.toByteArray()?.decodeToString()
        val accessibility = attributes?.get(KEY_ACCESSIBLE) as? String

        // A matched item whose data will not decode is not an absence either — refuse to mint over it.
        value?.let { SecureStoreRead.Found(it, protectionOf(accessibility)) }
            ?: SecureStoreRead.Unavailable("matched item did not decode")
    }

    /**
     * `kSecAttrAccessible`'s raw value → the port's platform-free [StoredProtection]. The comparison
     * is against [ACCESSIBLE_AFTER_FIRST_UNLOCK] itself, so the class this adapter *requires* and the
     * class it *recognises* cannot drift apart.
     *
     * The observed class is **logged** when it is not the required one. [StoredProtection.RESTRICTED]
     * deliberately does not say which class an item was filed under (the port would be carrying an
     * `kSecAttrAccessible` value inward only to be printed), so the detail is recorded here, where it
     * was read — once per legacy item per process, and never for a healthy one.
     */
    private fun protectionOf(accessibility: String?): StoredProtection = when (accessibility) {
        ACCESSIBLE_AFTER_FIRST_UNLOCK -> StoredProtection.BACKGROUND_READABLE
        null -> {
            log.i { "$service/$account reports no accessibility class; it will be upgraded in place" }
            StoredProtection.UNREPORTED
        }
        else -> {
            log.i { "$service/$account is stored `$accessibility`, not the required class — upgrading in place" }
            StoredProtection.RESTRICTED
        }
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

    /**
     * The item's **address** — the service, account and access group that [baseQuery] stamps on every
     * operation — keyed by the raw attribute names Security itself uses (`"svce"`, `"acct"`, `"agrp"`).
     *
     * Same argument as [writtenAttributes], for the other half of the item's identity: no test can ask
     * `securityd` where an item landed (a Kotlin/Native test binary is refused Keychain access
     * outright), so the only mechanical way to prove a seat still addresses the item the installed base
     * holds is to read back the fields the query is built from. Both this and [baseQuery] read the same
     * three properties, so they cannot drift.
     *
     * `null` under `"agrp"` is meaningful and is not the same as a missing entry: it is the unscoped
     * search — "wherever this process is entitled to look" — which is a legitimate but *inventoried*
     * choice (capability `architecture-guards`).
     */
    internal fun itemAddress(): Map<String, String?> = mapOf(
        KEY_SERVICE to service,
        KEY_ACCOUNT to account,
        KEY_ACCESS_GROUP to accessGroup,
    )

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
        if (status != errSecSuccess) throw SecureStoreUnavailable(diagnostic(status))
    }

    /**
     * `SecItemUpdate` changes the item's accessibility class and **nothing else** — the value is not
     * supplied, so it cannot be altered. This is what lets an already-provisioned device heal without
     * its device id changing (a new id would orphan its byte partition and its ledger).
     */
    override fun migrateProtection() {
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

    /**
     * The (class, service, account) triple that identifies the item — plus [accessGroup] when one is
     * named, which is what makes placement deterministic.
     *
     * Every operation is built from this one function (`read`, `write`'s add, `delete`, and
     * `migrateAccessibility`'s update), so the group cannot be applied to some operations and not
     * others. On an add it selects the destination group; on a search it narrows the scope to that
     * group alone, instead of spanning every group the process is entitled to and returning whichever
     * match the platform happens to surface first.
     */
    private fun baseQuery(): CFMutableDictionaryRef {
        val dict = newDictionary()
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, cfString(service))
        CFDictionaryAddValue(dict, kSecAttrAccount, cfString(account))
        accessGroup?.let { CFDictionaryAddValue(dict, kSecAttrAccessGroup, cfString(it)) }
        return dict
    }

    private fun newDictionary(): CFMutableDictionaryRef = CFDictionaryCreateMutable(
        null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    ) ?: error("could not allocate keychain query")

    private fun cfString(value: String) = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)

    private companion object {
        val log = co.touchlab.kermit.Logger.withTag("Keychain")

        /**
         * An `OSStatus` as the port's opaque diagnostic. It reaches a device log and an exception
         * message and nothing else — `SecureStoreRead.Unavailable` carries a `String` precisely so
         * that no caller can branch on Apple's numbering (decision record:
         * `changes/…/reshape-keychain-port`, D3). The raw number stays in the text, because
         * `-25308` is what a reader greps for.
         */
        fun diagnostic(status: Int): String = "OSStatus $status"

        /** The plain strings the CF attribute-key constants bridge to (`"v_Data"`, `"pdmn"`) — derived
         *  from the constants themselves rather than hardcoded. */
        val KEY_VALUE_DATA: String = CFBridgingRelease(CFRetain(kSecValueData)) as String
        val KEY_ACCESSIBLE: String = CFBridgingRelease(CFRetain(kSecAttrAccessible)) as String

        /** The address keys, likewise derived from the constants rather than hardcoded. */
        val KEY_SERVICE: String = CFBridgingRelease(CFRetain(kSecAttrService)) as String
        val KEY_ACCOUNT: String = CFBridgingRelease(CFRetain(kSecAttrAccount)) as String
        val KEY_ACCESS_GROUP: String = CFBridgingRelease(CFRetain(kSecAttrAccessGroup)) as String
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
