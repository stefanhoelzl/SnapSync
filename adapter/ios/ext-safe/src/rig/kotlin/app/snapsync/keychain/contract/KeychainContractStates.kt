@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.keychain.contract

import app.snapsync.contracts.Entered
import app.snapsync.contracts.SecureStoreContract
import app.snapsync.contracts.SecureStoreState
import app.snapsync.keychain.IosKeychain
import app.snapsync.keychain.KeychainApi
import app.snapsync.keychain.SHARED_KEYCHAIN_ACCESS_GROUP
import app.snapsync.ports.SecureStore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlocked
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecValueData

/**
 * The states the entitled app can put the Keychain in, per clause. Shared by the device binding (which
 * records) and the replay binding (which replays), so seeding and cleanup make IDENTICAL calls in both —
 * otherwise every replay would diverge on the setup rather than on the adapter.
 *
 * The item's address is derived from the clause id, in the shared group the production device id uses.
 */

/** Why neither the device binding nor its replay can present INACCESSIBLE. */
internal const val DEVICE_UNREACHABLE_INACCESSIBLE =
    "the entitled app runs unlocked: its Keychain is accessible (the kexe host covers INACCESSIBLE)"

private const val SERVICE = "app.snapsync.contract"

/**
 * A fresh [IosKeychain] over [keychain] in [state]. Starts with a delete — a clause is never allowed to see
 * an item a previous, interrupted run left behind — and deletes again on dispose. [afterDispose] runs last
 * (the replay binding checks the recording was exhausted there).
 */
internal fun keychainInState(
    keychain: KeychainApi,
    state: SecureStoreState,
    clauseId: String,
    afterDispose: () -> Unit = {},
): Entered<SecureStore> {
    if (state == SecureStoreState.INACCESSIBLE) return Entered.Unreachable(DEVICE_UNREACHABLE_INACCESSIBLE)
    val store = IosKeychain(SERVICE, clauseId, SHARED_KEYCHAIN_ACCESS_GROUP, keychain)
    store.delete()
    val seed = SecureStoreContract.seedValue(clauseId)
    when (state) {
        SecureStoreState.HOLDING_BACKGROUND_READABLE -> store.write(seed)
        SecureStoreState.HOLDING_RESTRICTED -> legacyItem(clauseId, seed).let { item ->
            keychain.add(item)
            CFRelease(item)
        }
        else -> Unit
    }
    return Entered.Ready(store) {
        store.delete()
        afterDispose()
    }
}

/**
 * An item as a build before the locked-device fix filed it: `kSecAttrAccessibleWhenUnlocked`, the iOS
 * default — the class that made every background read fail while locked, and that the upgrade exists to
 * move items off. Same address [IosKeychain] uses.
 */
private fun legacyItem(account: String, value: String): CFDictionaryRef {
    val attributes = mapOf(
        bridged(kSecClass) to bridged(kSecClassGenericPassword),
        bridged(kSecAttrService) to SERVICE,
        bridged(kSecAttrAccount) to account,
        bridged(kSecAttrAccessGroup) to SHARED_KEYCHAIN_ACCESS_GROUP,
        bridged(kSecAttrAccessible) to bridged(kSecAttrAccessibleWhenUnlocked),
        bridged(kSecValueData) to NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding),
    )
    // +1: the caller releases it once the seam has been handed it (the seam copies what it records).
    return checkNotNull(CFBridgingRetain(attributes)).reinterpret()
}

private fun bridged(constant: platform.CoreFoundation.CFStringRef?): String =
    CFBridgingRelease(CFRetain(constant)) as String
