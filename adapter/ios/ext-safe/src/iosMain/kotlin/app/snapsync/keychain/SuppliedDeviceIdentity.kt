package app.snapsync.keychain

import app.snapsync.logging.appGroupDirectory
import kotlinx.cinterop.ExperimentalForeignApi
import co.touchlab.kermit.Logger
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * The file a supplied identity is read from — **read-only here, and written by nothing that ships**.
 *
 * Its presence is the entire discriminator. A production build never creates it, so on any real device it
 * is absent and this whole path is inert by construction rather than by a flag. The control channel writes
 * it (`:test:rig`, `POST /device/identity`), and that module is absent from a production build too.
 *
 * It lives in the App Group container because that is what the app and the upload extension share, so one
 * file serves both processes rather than needing a second mechanism for the appex.
 */
private const val IDENTITY_FALLBACK_FILE = "device-identity-fallback"

/**
 * Resolve this device's identity: the secure store first, then a supplied fallback if there is one.
 *
 * ## Why the fallback cannot live inside [KeychainDeviceIdentity]
 *
 * Because there is **no absence to fill**. The original design for this (`SNAPSYNC_DEVICE_ID`, in the rig's
 * decision record) proposed filling an absent Keychain item, and that could never have worked on the host
 * that needs it: a simulator answers `errSecMissingEntitlement` (-34018), which is a **read error**, not
 * `errSecItemNotFound`. `KeychainDeviceIdentity` deliberately never mints on a read error — that
 * distinction IS the locked-device fix — so the value never reaches a branch that would consult a fallback.
 * `resolveOrMint`'s adopt and mint branches also both write, so on such a host every branch fails, not only
 * the read.
 *
 * So the fallback sits one level up, at the supplier, where the failure is visible as a failure.
 *
 * ## What each host sees
 *
 * - **Locked device**: the store reports unavailable, no file is present, this rethrows, and the app defers
 *   exactly as it does today. That deferral is load-bearing and must not regress.
 * - **Mis-signed build**: the store reports unavailable, no file is present, and resolution fails loudly.
 * - **A host given an identity**: the file is present and its value is adopted verbatim.
 * - **A healthy device**: the store resolves, and the file — which is not there anyway — is never consulted.
 *
 * ## Fills an absence; never overwrites
 *
 * A successful store read wins unconditionally and the fallback is not even looked at. The identity is
 * written once and is unrecoverable if replaced, so a supplied value must never be able to take precedence
 * over a real one.
 */
fun resolveDeviceIdentity(
    role: DeviceIdentityRole,
    log: Logger = Logger.withTag("DeviceIdentity"),
): String {
    val fromStore = runCatching { KeychainDeviceIdentity(role).deviceId() }
    fromStore.getOrNull()?.let { return it }

    val supplied = suppliedIdentity()
    if (supplied == null) {
        // Rethrow the STORE's failure, not a synthesized one: its message distinguishes an unavailable
        // store from a locked one, and that distinction is what tells an operator which thing to fix.
        throw fromStore.exceptionOrNull() ?: IllegalStateException("no device identity and no supplied one")
    }
    log.w {
        "device identity SUPPLIED from $IDENTITY_FALLBACK_FILE — the secure store could not serve one " +
            "(${fromStore.exceptionOrNull()?.message}). This file is written by the dev control channel " +
            "and by nothing that ships, so on a real device this line cannot appear."
    }
    return supplied
}

/** The supplied identity, or `null` when there is no App Group container or no file in it. */
@OptIn(ExperimentalForeignApi::class)
private fun suppliedIdentity(): String? {
    val group = appGroupDirectory() ?: return null
    return NSString.stringWithContentsOfFile(
        path = "$group/$IDENTITY_FALLBACK_FILE",
        encoding = NSUTF8StringEncoding,
        error = null,
    )?.trim()?.takeIf { it.isNotBlank() }
}
