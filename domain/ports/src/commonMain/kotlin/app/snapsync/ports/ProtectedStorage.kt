package app.snapsync.ports

/**
 * Whether the device's **protected storage** is readable right now (capability `ios-app-shell`, "Background entry
 * points record protected-data state").
 *
 * Until the first unlock after a boot the operating system keeps protected files — the ledger, the config file, the
 * Keychain — encrypted, and a background wake can land in exactly that window. Every protected read already tells
 * *unreadable* from *absent*, so nothing decides on this answer: it exists so a background entry point can write it
 * into the device log, which is the only way to see after the fact that a failed wake ran on a locked device.
 *
 * Named for the need: iOS answers it with `UIApplication.isProtectedDataAvailable`; an Android binding would ask
 * whether the user has unlocked since boot.
 */
interface ProtectedStorage {
    /** `true` while protected storage can be read. May hop to whatever thread the platform requires. */
    suspend fun readable(): Boolean
}
