@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.background

import app.snapsync.objc.objcBoundary
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import co.touchlab.kermit.Logger
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The operating-system boundary of [IosBackgroundTime]: `UIApplication`'s background-task pair, and nothing else,
 * so the adapter's own logic — once-only expiry and end, a refusal read as an immediate expiry — is testable over a
 * double in the simulator's test executable, which has no `UIApplication` at all.
 */
internal interface BackgroundTimeApi {
    /** Begins a background task; answers its identifier, or `null` where the system refused one. */
    fun begin(name: String, expirationHandler: () -> Unit): ULong?

    /** Ends the background task [identifier]. */
    fun end(identifier: ULong)
}

/**
 * The real one. Both calls are documented safe from any thread (*"This method can be safely called on a non-main
 * thread"*, on `beginBackgroundTask(withName:expirationHandler:)` and `endBackgroundTask(_:)` alike), so neither
 * names a lane; the expiration handler is invoked by the system on the main thread.
 */
internal object SystemBackgroundTimeApi : BackgroundTimeApi {
    override fun begin(name: String, expirationHandler: () -> Unit): ULong? =
        UIApplication.sharedApplication.beginBackgroundTaskWithName(name, expirationHandler)
            .takeIf { it != UIBackgroundTaskInvalid }

    override fun end(identifier: ULong) = UIApplication.sharedApplication.endBackgroundTask(identifier)
}

/**
 * The iOS [BackgroundTime]: `UIApplication.beginBackgroundTask(withName:expirationHandler:)` /
 * `endBackgroundTask(_:)` (`docs/architecture.md`, "Background time is an outbound port named for the need";
 * decision record `changes/own-work-per-wake`, D3 and D5).
 *
 * **App-only.** `UIApplication` is unavailable to app extensions, and the upload extension has no such signal to
 * offer anyway (capability `background-upload`), so this lives in `:adapter:ios:app-only` and the extension's
 * composition binds nothing for the port.
 *
 * **The expiration handler only requests the stop.** Apple calls it *"shortly before the app's remaining background
 * time reaches 0"*, on the main thread, and expects it back promptly; so it invokes the core's `onExpiry` — which
 * requests a cooperative stop and returns — and returns itself. The adapter does not end the task; the core does,
 * through [BackgroundTimeHold.end], and it does so **at once, from inside that same `onExpiry`**: it requests the
 * tail's stop, releases the OS handler it guards and ends the hold without waiting for the unit in flight, which runs
 * on until iOS suspends the process (every unit is a safe retry). That is Apple's own recipe — end the task inside
 * the handler — and it leaves nothing for the watchdog to decide: a task not ended *"before time expires"* gets the
 * app killed (decision record `changes/own-work-per-wake`, D4 as amended at apply).
 *
 * **A refusal is an immediate expiry.** `beginBackgroundTask` answers `UIBackgroundTaskInvalid` when the app may
 * not run in the background — its time is already up. That is reported through `onExpiry`, before [begin] returns,
 * rather than as an error: it means exactly what an expiry means to the caller, and the hold it returns ends
 * nothing.
 *
 * **Ended exactly once.** A hold's [BackgroundTimeHold.end] ends its task on the first call and does nothing after,
 * so a core that ends on more than one path (its work finished; an expiry stopped it) cannot end the task twice —
 * nor end a later task that reuses the identifier, which the platform recycles.
 *
 * WHAT IS CONTRACTED, AND WHAT CANNOT BE. `BackgroundTimeContract` runs live in the simulator app, where the app's
 * time is not up: a hold is granted, two holds do not refuse each other, and ending twice is quiet. No host lets a
 * binding enter "time is up" — the system fires the handler only after the app has been in the background as long
 * as it allows, which takes it away from the rig — so the expiry path is held by this module's tests over
 * [BackgroundTimeApi], and by nothing that ran on an operating system. Whether the handler fires in the simulator's
 * background-transfer relaunch setting is an open question of the design (`changes/own-work-per-wake`).
 */
class IosBackgroundTime internal constructor(
    private val log: Logger,
    // The operating-system boundary; production always passes the real one.
    private val api: BackgroundTimeApi,
) : BackgroundTime {

    constructor(log: Logger) : this(log, SystemBackgroundTimeApi)

    override fun begin(label: String, onExpiry: () -> Unit): BackgroundTimeHold {
        val expiry = Once(onExpiry)
        val identifier = api.begin(label) {
            objcBoundary(log, "backgroundTime.expiry($label)") {
                log.w { "$label: the operating system says background time is up — requesting a stop" }
                expiry.run()
            }
        }
        if (identifier == null) {
            log.w { "$label: background time refused — the app's time is already up; reported as an expiry" }
            expiry.run()
            return Refused
        }
        return Held(identifier)
    }

    /** A hold on the system's task [identifier], ended with the system exactly once. */
    private inner class Held(private val identifier: ULong) : BackgroundTimeHold {
        private val ended = AtomicBoolean(false)

        override fun end() {
            if (ended.compareAndSet(expectedValue = false, newValue = true)) api.end(identifier)
        }
    }

    /** The hold a refusal returns: there is no task to end. */
    private object Refused : BackgroundTimeHold {
        override fun end() = Unit
    }

    /** [action], run at most once however many callers race to run it. */
    private class Once(private val action: () -> Unit) {
        private val ran = AtomicBoolean(false)

        fun run() {
            if (ran.compareAndSet(expectedValue = false, newValue = true)) action()
        }
    }
}
