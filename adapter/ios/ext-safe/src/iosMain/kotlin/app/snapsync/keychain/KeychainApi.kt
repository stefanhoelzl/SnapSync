@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.keychain

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate

/**
 * **The operating-system boundary of [IosKeychain]**: the four `SecItem*` calls it makes, and nothing else
 * (capability `port-contracts`, "Hosts CI cannot reach are recorded at the operating-system boundary and
 * replayed on every build").
 *
 * It exists so the adapter can be run against a *recording* of what iOS answered on an entitled device.
 * The seam sits below every decision the adapter makes — the queries it builds, how it classifies a
 * status, how it reads protection back — so a replay exercises the CURRENT adapter code against the
 * device's real answers, and a change in what the adapter asks iOS shows up as a divergence rather than
 * as a stale green.
 *
 * `internal`: nothing outside this module may reach the Keychain (capability `architecture-guards`), and
 * the recording and replaying implementations live in this module's rig-gated source set and its tests.
 */
internal interface KeychainApi {
    fun add(attributes: CFDictionaryRef?): Int

    /** [result] receives a +1 reference the caller releases, exactly as `SecItemCopyMatching` hands it out. */
    fun copyMatching(query: CFDictionaryRef?, result: CPointer<CFTypeRefVar>?): Int

    fun update(query: CFDictionaryRef?, attributes: CFDictionaryRef?): Int

    fun delete(query: CFDictionaryRef?): Int
}

/** The real Keychain. The only implementation a production build contains. */
internal object SystemKeychainApi : KeychainApi {
    override fun add(attributes: CFDictionaryRef?): Int = SecItemAdd(attributes, null)

    override fun copyMatching(query: CFDictionaryRef?, result: CPointer<CFTypeRefVar>?): Int =
        SecItemCopyMatching(query, result)

    override fun update(query: CFDictionaryRef?, attributes: CFDictionaryRef?): Int = SecItemUpdate(query, attributes)

    override fun delete(query: CFDictionaryRef?): Int = SecItemDelete(query)
}
