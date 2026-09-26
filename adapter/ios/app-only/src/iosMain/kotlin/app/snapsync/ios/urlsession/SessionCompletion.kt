@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.ios.urlsession

import app.snapsync.objc.objcBoundary
import app.snapsync.ports.Completion
import co.touchlab.kermit.Logger
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * `handleEventsForBackgroundURLSession`'s completion handler as a [Completion]: released once, **on the main thread**
 * — *"Because the provided completion handler is part of UIKit, you must call it on your main thread"*
 * (`UIApplicationDelegate.application(_:handleEventsForBackgroundURLSession:completionHandler:)`). The release is
 * requested wherever the core decides it (after the session's drain report, on a session-owned queue; or on the
 * background-time expiry), so this adapter is what puts it where UIKit requires — the one reason it names the main
 * thread.
 *
 * A background-session relaunch carries no expiry signal of its own ([onExpired] never runs): its only "time is up" is
 * the process's background time, which the core holds across the wake.
 */
internal class SessionCompletion(
    private val handler: () -> Unit,
    private val log: Logger = Logger.withTag("SessionCompletion"),
) : Completion {
    private val released = AtomicBoolean(false)

    override fun complete() {
        if (!released.compareAndSet(expectedValue = false, newValue = true)) return
        dispatch_async(dispatch_get_main_queue()) { objcBoundary(log, "sessionCompletion.release") { handler() } }
    }

    override fun onExpired(action: () -> Unit) = Unit
}
