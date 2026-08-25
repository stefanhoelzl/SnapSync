package app.snapsync.keychain

import app.snapsync.ports.SecureStore

/**
 * The device target's binding: the addressed shared-Keychain item, exactly as before this seam
 * existed (capability `device-identity`).
 *
 * Every shipped binary — TestFlight, App Store, and every sideloaded dev build — compiles this
 * actual and only this one. The `iosSimulatorArm64` file store is not merely unused here; it is
 * absent from the binary.
 */
internal actual fun deviceIdPrimaryStore(): SecureStore =
    KeychainDeviceIdentity.deviceIdItem(SHARED_KEYCHAIN_ACCESS_GROUP)

/**
 * The unscoped view of the same item — the repair path for an id an older build placed in this
 * process's own `application-identifier` group. Consulted only on a genuine absence, and only by
 * [DeviceIdentityRole.MINTING]. Decision record: `changes/archive/2026-07-20-fix-split-device-identity`.
 */
internal actual fun deviceIdLegacyStore(): SecureStore =
    KeychainDeviceIdentity.deviceIdItem(accessGroup = null)
