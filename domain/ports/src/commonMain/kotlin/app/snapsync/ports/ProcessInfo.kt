package app.snapsync.ports

import app.snapsync.model.Availability
import app.snapsync.model.MemoryFootprint

/**
 * What the operating system says about this process right now — ONE external system,
 * the OS's view of the running process.
 *
 * [protectedDataAvailable]: until the first unlock after a boot the operating system keeps protected files — the
 * ledger, the config file, the Keychain — encrypted, and a background wake can land in exactly that window. Every
 * protected read already tells *unreadable* from *absent*, so nothing decides on this answer: a background entry
 * point writes it into the device log, which is the only way to see after the fact that a failed wake ran on a
 * locked device. Only the app asks it: an app extension has no `UIApplication`, and composes no [ProcessInfo].
 *
 * Named for the need: iOS answers with `UIApplication.isProtectedDataAvailable`; an Android binding would ask
 * whether the user has unlocked since boot.
 *
 * [memoryFootprint]: what the platform charges this process for in memory right now. The app records it as it
 * settles in the background, for the process-metric report that later says a suspended app was ended for memory.
 * `null` where nothing is answered.
 */
interface ProcessInfo : Port {
    /** Whether protected storage can be read right now. May hop to whatever thread the platform requires. */
    suspend fun protectedDataAvailable(): Availability

    /** This process's own memory accounting, or `null` where the platform's is not read. Any thread; never blocks. */
    fun memoryFootprint(): MemoryFootprint?
}
