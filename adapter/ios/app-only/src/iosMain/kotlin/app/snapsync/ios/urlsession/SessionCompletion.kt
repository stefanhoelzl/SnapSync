@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.ios.urlsession

import app.snapsync.objc.onQueue
import app.snapsync.ports.Completion
import co.touchlab.kermit.Logger
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
    /** How the handler reaches the main thread: [MainThreadRelease], or a session's own, which records or replays it. */
    private val release: HandlerRelease,
) : Completion {
    private val released = AtomicBoolean(false)

    override fun complete() {
        if (!released.compareAndSet(expectedValue = false, newValue = true)) return
        release.release(handler)
    }
}

/** Releases an operating-system completion handler where the platform requires. */
internal fun interface HandlerRelease {
    fun release(handler: () -> Unit)
}

/** On the main queue, where UIKit requires a session relaunch's handler to be called. */
internal object MainThreadRelease : HandlerRelease {
    private val log = Logger.withTag("SessionCompletion")

    override fun release(handler: () -> Unit) = onQueue(
        dispatch_get_main_queue(),
        log,
        "sessionCompletion.release",
        handler,
    )
}
