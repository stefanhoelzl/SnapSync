package app.snapsync.android.storage

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import app.snapsync.ports.PlatformDeviceId
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * The Android [PlatformDeviceId]: a name-based (version 5) UUID over `Settings.Secure.ANDROID_ID`, in the upper-case
 * canonical form every minted device id has.
 *
 * `ANDROID_ID` is scoped to this app's signing key, the user and the device: it **survives a reinstall** and resets on a
 * factory reset. That is the lifetime a device id has on iOS, whose this-device-only Keychain slot survives a
 * reinstall too — and it is the only lifetime Android offers the app, whose own storage goes with the install (Auto
 * Backup is off). So a reinstalled phone stays the same device to its events: the same capacity slot, the same shared
 * photos. It is not linkable across apps (another signing key sees another value), and the id the backend sees is a
 * digest of it, not the value.
 *
 * `null` when the platform answers nothing — the identity service then mints a random id, as on iOS.
 */
class AndroidPlatformDeviceId(private val androidId: () -> String?) : PlatformDeviceId {

    /** Production: the platform's value for this app (a secondary constructor, not a default). */
    @SuppressLint("HardwareIds") // the identity this is for: see the class comment
    constructor(
        context: Context,
    ) : this({ Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) })

    override fun stableId(): String? = androidId()?.trim()?.takeIf { it.isNotEmpty() }?.let { deviceIdFor(it) }

    companion object {
        /**
         * The namespace the ids are derived in — runtime identity: every Android device id is a function of it, and a
         * re-valued one gives every installed device a new identity on its next mint.
         */
        val NAMESPACE: UUID = UUID.fromString("5e1f4d0a-7c3b-4c52-9a6e-3f0b8d2a1c47")

        /** The RFC 9562 version-5 UUID of [androidId] in [NAMESPACE], upper-case. */
        fun deviceIdFor(androidId: String): String {
            val digest = MessageDigest.getInstance("SHA-1").apply {
                update(
                    ByteBuffer.allocate(
                        16,
                    ).putLong(NAMESPACE.mostSignificantBits).putLong(NAMESPACE.leastSignificantBits).array(),
                )
                update(androidId.encodeToByteArray())
            }.digest()
            digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
            digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
            val bytes = ByteBuffer.wrap(digest, 0, 16)
            return UUID(bytes.long, bytes.long).toString().uppercase()
        }
    }
}
