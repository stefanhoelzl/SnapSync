package app.snapsync.ports

import app.snapsync.model.Availability

/**
 * What the operating system says about this process right now (capability `sync-status`) — ONE external system,
 * the OS's view of the running process.
 *
 * [protectedDataAvailable]: until the first unlock after a boot the operating system keeps protected files — the
 * ledger, the config file, the Keychain — encrypted, and a background wake can land in exactly that window. Every
 * protected read already tells *unreadable* from *absent*, so nothing decides on this answer: a background entry
 * point writes it into the device log, which is the only way to see after the fact that a failed wake ran on a
 * locked device. [Availability.UNKNOWN] where the process cannot ask (an app extension has no `UIApplication`).
 *
 * Named for the need: iOS answers with `UIApplication.isProtectedDataAvailable`; an Android binding would ask
 * whether the user has unlocked since boot.
 */
interface ProcessInfo {
    /** Whether protected storage can be read right now. May hop to whatever thread the platform requires. */
    suspend fun protectedDataAvailable(): Availability
}
