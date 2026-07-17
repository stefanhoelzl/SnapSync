package app.snapsync.keychain

import platform.Foundation.NSUUID

/**
 * The stable per-install device identity (capability `device-identity`): persists the device id as a
 * single Keychain generic-password item (encrypted at rest, survives app updates, process death,
 * **and reinstall**). [deviceId] is a UUID minted **once** and persisted, identical across the app and
 * the upload extension (one shared Keychain item). It is the `/files/devices/<deviceId>/` byte-store
 * partition and the per-event device-manifest key (`/events/<eventId>/devices/<deviceId>.json`) — the
 * "which manifest is mine" handle a future restore needs to tell this device's own (possibly-deleted)
 * photos from another contributor's. On first resolution it mints an `NSUUID` and writes it;
 * thereafter it reads the same value back. Use sites take the id as a plain `() -> String` supplier —
 * there is no interface to implement, and tests inject a lambda.
 *
 * All Keychain access goes through this module — the only one permitted to touch `SecItem*`
 * (capability `architecture-guards`) — which is what buys the three properties this logic used to get
 * wrong:
 *
 * - **Background-readable.** The item is stored `kSecAttrAccessibleAfterFirstUnlock`, so the id
 *   resolves during a background wake on a **locked** device. Under the old iOS default
 *   (`WhenUnlocked`) it did not — and background work is precisely when this is read.
 * - **A read error never mints.** Only a genuine `errSecItemNotFound` mints. Previously *any* failed
 *   read was mapped to "no id stored", so a locked read minted a fresh UUID and then aborted the
 *   process trying to persist it. Had that write ever succeeded, the device would have silently
 *   acquired a **new identity** — orphaning its `/files/devices/<deviceId>/` partition and its ledger,
 *   and re-uploading the whole library. An unreadable Keychain now raises `KeychainUnavailable`, which
 *   the composition roots handle (the app defers; the extension skips its cycle).
 * - **Legacy items heal.** An id written by an older build under the weaker class is upgraded in place
 *   with its **value preserved** — never re-minted, so the partition and ledger stay valid.
 *
 * The id is deliberately **restorable from an encrypted backup** (not `…ThisDeviceOnly`): the app
 * container — the ledger and the discovery cursor — is restored alongside it, and an id that did *not*
 * survive the restore would leave a restored device with a fresh identity but a ledger claiming
 * everything is already uploaded, so it would upload nothing while its manifest sat empty.
 * Decision record: `changes/archive/…-fix-locked-device-keychain-access`.
 *
 * No `kSecAttrAccessGroup` is set: the shared keychain-access-group is the app's first entitlement
 * entry, so the item lands there by default and the upload extension reads the **same** id.
 *
 * The resolved id is cached for the process lifetime (one read/mint per process).
 */
class KeychainDeviceIdentity(
    private val keychain: Keychain = IosKeychain(service = "app.snapsync.deviceid", account = "deviceid"),
) {

    private val cached: String by lazy {
        resolveOrMint(keychain, ACCESSIBLE_AFTER_FIRST_UNLOCK) { NSUUID().UUIDString() }
    }

    fun deviceId(): String = cached
}
