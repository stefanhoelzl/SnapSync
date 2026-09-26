package app.snapsync.ports

import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger

/**
 * **The operating system's scheduled wakes of this app** (`docs/architecture.md`, "Background execution"): ask it to
 * wake the app later, cancel that request, and hear it when it does. On iOS a `BGProcessingTask` through
 * `BGTaskScheduler`; on Android a one-shot `WorkManager` request.
 *
 * One external system, deciding nothing. **When** a wake is wanted — after a tail that left work, never after one
 * that declined — and what its delay and network requirement are, is the `Heartbeat` service's (`:domain:services`);
 * what a wake runs is the composition's handler. A platform without a kind of wake answers [ScheduleResult.Unsupported]
 * for it, and the services arm and cancel every [WakeId] on every platform, so there is no platform branch above the
 * port (iOS has no library-change wake; Android has both).
 *
 * An event port: [listen] registers the composition's handlers once per adapter, and on iOS that registration is the
 * `BGTaskScheduler` launch-handler registration Apple requires before the app finishes launching — so the composition
 * that registers it is built at launch (`SnapSyncRoot.onLaunch`).
 */
interface Wake : Listenable<WakeHandlers> {

    /**
     * Ask for [id] to wake the app when [trigger] allows. Idempotent: a request for an [id] already pending replaces
     * it rather than stacking a second one.
     */
    fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult

    /** Withdraw any pending request for [id]. Cancelling nothing is not a failure. */
    fun cancel(id: WakeId)
}

/**
 * What the operating system tells the core about its wakes. Built only by a composition (law "Commands cross one
 * door").
 */
class WakeHandlers(
    /**
     * The operating system woke the app for [id], and handed [completion] — held by the core across the wake's work
     * (its tail) and released after it, or at once on the operating system's expiry ([Completion.onExpired]).
     */
    val onWake: (id: WakeId, completion: Completion) -> Unit,
)
