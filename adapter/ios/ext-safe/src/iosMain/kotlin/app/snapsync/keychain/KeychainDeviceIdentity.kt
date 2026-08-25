package app.snapsync.keychain

import app.snapsync.ports.DeviceIdentityAbsent
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreResolution
import app.snapsync.ports.readExisting
import app.snapsync.ports.resolveOrMint

import platform.Foundation.NSUUID

/**
 * The Keychain access group both processes share (`keychain-access-groups` in **both** entitlements
 * files, as `$(AppIdentifierPrefix)app.snapsync.shared`).
 *
 * Named explicitly rather than left to the platform's default, and pinned as a runtime-identity
 * literal (capability `architecture-guards`) because it is part of the item's identity: re-valuing it
 * strands every device in the field exactly as re-valuing the service or account would, and it does so
 * **silently** — the item is simply written to a different real group, where every read still
 * succeeds and merely returns a different item.
 *
 * The guard composes it from `TEAM_ID` in the GENERATED `Deployment.xcconfig` plus the group declared
 * in the two entitlements files, so the three cannot drift apart unnoticed.
 */
const val SHARED_KEYCHAIN_ACCESS_GROUP: String = "E9Z8BADH58.app.snapsync.shared"

/**
 * Which process is resolving the identity — and therefore whether it may create one.
 *
 * The right to mint is a per-process privilege, not a per-call flag, which is why it is stated at
 * construction: a process that may mint must be able to tell a genuinely new install from one whose
 * existing id it simply cannot reach, and only the app can.
 */
enum class DeviceIdentityRole {

    /**
     * The app. Reads the shared group, adopts an out-of-group id if it finds one, and mints only when
     * the id exists nowhere it can reach. The sole minter.
     */
    MINTING,

    /**
     * The upload extension. Reads the shared group and nothing else — it neither adopts nor mints,
     * because it cannot distinguish "this device has no identity yet" from "the app's identity is not
     * reachable from here", and acting on that ambiguity is what produced two identities.
     *
     * On absence it raises [app.snapsync.ports.DeviceIdentityAbsent], which the cycle gate treats
     * exactly as it treats an unreadable Keychain: skip the cycle, create no upload jobs, retry next
     * invocation. The app resolves the identity on every launch, so the wait is bounded.
     */
    READ_ONLY,
}

/**
 * The stable per-install device identity (capability `device-identity`): persists the device id as a
 * single Keychain generic-password item (encrypted at rest, survives app updates, process death,
 * **and reinstall**). [deviceId] is a UUID minted **once** and persisted. It is the
 * `/files/devices/<deviceId>/` byte-store partition and the per-event device-manifest key
 * (`/events/<eventId>/devices/<deviceId>.json`) — the "which manifest is mine" handle a future restore
 * needs to tell this device's own (possibly-deleted) photos from another contributor's. Use sites take
 * the id as a plain `() -> String` supplier — there is no interface to implement, and tests inject a
 * lambda.
 *
 * All Keychain access goes through this module — the only one permitted to touch `SecItem*`
 * (capability `architecture-guards`) — which is what buys the properties this logic used to get wrong:
 *
 * - **Background-readable.** The item is stored `kSecAttrAccessibleAfterFirstUnlock`, so the id
 *   resolves during a background wake on a **locked** device. Under the old iOS default
 *   (`WhenUnlocked`) it did not — and background work is precisely when this is read.
 * - **A read error never mints.** Only a genuine `errSecItemNotFound` may mint. Previously *any* failed
 *   read was mapped to "no id stored", so a locked read minted a fresh UUID and then aborted the
 *   process trying to persist it. An unreadable Keychain now raises `SecureStoreUnavailable`, which the
 *   composition roots handle (the app defers; the extension skips its cycle).
 * - **Legacy items heal.** An id written by an older build under the weaker class is upgraded in place
 *   with its **value preserved** — never re-minted, so the partition and ledger stay valid.
 * - **Placement is addressed, not assumed.** The item is read and written in
 *   [SHARED_KEYCHAIN_ACCESS_GROUP] by name.
 *
 * That last property is the one that failed in the field. This class used to claim the id was
 * *"identical across the app and the upload extension (one shared Keychain item)"* because
 * *"the shared keychain-access-group is the app's first entitlement entry, so the item lands there by
 * default"*. On 2026-07-20 an SE2 ran nine hours with the app on one id and the extension on another,
 * across four events, **both reading successfully** — so every photo the device uploaded came back as a
 * foreign asset and was re-imported into its owner's library as a duplicate. Unnamed placement is
 * resolved at *write* time from the writing build's entitlements, which drift; naming the group makes
 * it a property of this code instead. Decision record: `changes/archive/2026-07-20-fix-split-device-identity`.
 *
 * The id is deliberately **restorable from an encrypted backup** (not `…ThisDeviceOnly`): the app
 * container — the ledger and the discovery cursor — is restored alongside it, and an id that did *not*
 * survive the restore would leave a restored device with a fresh identity but a ledger claiming
 * everything is already uploaded, so it would upload nothing while its manifest sat empty.
 * Decision record: `changes/archive/…-fix-locked-device-keychain-access`.
 *
 * **Two roles, and only one may create an identity** — see [DeviceIdentityRole]. The resolved id is
 * cached for the process lifetime (one resolve per process).
 */
class KeychainDeviceIdentity(
    private val role: DeviceIdentityRole,
    /**
     * Where the id is kept. Defaults to the **compilation target's** store ([deviceIdPrimaryStore]):
     * the addressed shared-Keychain item on `iosArm64` — every shipped binary — and an App-Group file
     * on `iosSimulatorArm64`, where that group cannot exist at all. The resolution below is identical
     * either way; only the store moves. See [deviceIdPrimaryStore] for the measurement.
     */
    private val shared: SecureStore = deviceIdPrimaryStore(),
    /**
     * The adoption source, consulted **only** by [DeviceIdentityRole.MINTING] and only when [shared]
     * reports absence. On the device target it is the unscoped view of the same item — spanning every
     * group this process is entitled to, so it can still see an id an older build placed in the
     * process's own `application-identifier` group. A target with no such history answers `Absent`.
     */
    private val legacy: SecureStore = deviceIdLegacyStore(),
    private val mint: () -> String = { NSUUID().UUIDString() },
) {

    private val cached: String by lazy {
        var resolution: SecureStoreResolution? = null

        val id = when (role) {
            // Asks the shared group and accepts its answer — no legacy read, because adopting from an
            // unscoped search here would find this process's OWN stale item and re-create the very
            // split this class exists to close.
            DeviceIdentityRole.READ_ONLY ->
                readExisting(shared, onResolution = { resolution = it })
                    ?: run {
                        log.i { "device identity: absent in the shared group and this process may not mint" }
                        throw DeviceIdentityAbsent()
                    }

            DeviceIdentityRole.MINTING -> resolveOrMint(
                shared,
                onResolution = { resolution = it },
                readLegacy = { legacy.read() },
                generate = mint,
            )
        }

        // Logged verbatim, once per process, by BOTH the app and the extension — the id is the whole
        // point of the line. The two processes are supposed to print the same id; when they do not,
        // this is the only place that says so, and for nine hours in July nothing did.
        log.i { "device identity: id=$id via=${resolution.describe()}" }
        id
    }

    fun deviceId(): String = cached

    companion object {
        /**
         * The one construction site of the device-id item, so the pinned (service, account) pair
         * stays single-sited (capability `architecture-guards`) even though the identity is now read
         * through two views of it — the addressed one and the unscoped legacy one. [accessGroup]
         * `null` means "search wherever this process is entitled to look".
         *
         * `internal` (and typed as the impl) so `iosTest` can read back the address it builds — the
         * only mechanical check that this seat still names the group and the (service, account) pair
         * the installed base's item is filed under. That check cannot be made through the port type,
         * and it cannot be made through `securityd` either (see [IosKeychain.itemAddress]).
         */
        internal fun deviceIdItem(accessGroup: String?): IosKeychain =
            IosKeychain(service = "app.snapsync.deviceid", account = "deviceid", accessGroup = accessGroup)

        private val log = co.touchlab.kermit.Logger.withTag("deviceIdentity")

        private fun SecureStoreResolution?.describe(): String = when (this) {
            is SecureStoreResolution.Found ->
                "read(protection=$protection${if (migrated) ", migrated" else ""})"
            SecureStoreResolution.Adopted -> "adopted(from an out-of-group item)"
            SecureStoreResolution.Minted -> "minted"
            null -> "unreported"
        }
    }
}
