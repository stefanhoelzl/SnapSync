package app.snapsync.contract

import platform.UIKit.UIApplication
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync

/*
 * The device runs' reads of the app's own state — whether it is in the background, whether its
 * protected data is sealed — taken by the run, unrecorded, to know the person has put the phone in the state the run
 * records. `UIApplication` is main-thread-only, so each read is made there, from the run's own thread.
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true`, and into `iosTest` otherwise.
 */

private fun <T> onMain(read: () -> T): T {
    var value: T? = null
    dispatch_sync(dispatch_get_main_queue()) { value = read() }
    @Suppress("UNCHECKED_CAST")
    return value as T
}

/** The app is in the background — the person went to the home screen, or locked the phone. */
internal fun appInBackground(): Boolean =
    onMain {
        UIApplication.sharedApplication.applicationState == platform.UIKit.UIApplicationState.UIApplicationStateBackground
    }

/** Whether the app's protected data is readable: not while the phone is locked, with a passcode set. */
internal fun protectedDataReadable(): Boolean = onMain { UIApplication.sharedApplication.isProtectedDataAvailable() }
