package app.snapsync.ports

import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Granted
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
 * An event port: [inbound] registers the composition's handlers once per adapter, and on iOS that registration is the
 * `BGTaskScheduler` launch-handler registration Apple requires before the app finishes launching — so the composition
 * that registers it is built at launch (`SnapSyncRoot.onLaunch`).
 */
interface Wake : EventPort<WakeHandlers> {

    /**
     * Ask for [id] to wake the app when [trigger] allows. Idempotent: a request for an [id] already pending replaces
     * it rather than stacking a second one.
     *
     * Suspending, because an answer may take the platform a while: Android's is only true once WorkManager has
     * registered the request, and an Android caller is often on the main thread, where waiting by blocking froze the
     * app (SNAPSYNC-40). An adapter suspends for that answer and never blocks its caller's thread.
     */
    suspend fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult

    /** Withdraw any pending request for [id]. Cancelling nothing is not a failure. */
    fun cancel(id: WakeId)
}

/**
 * What the operating system tells the core about its wakes. Built only by a composition (law "Commands cross one
 * door").
 */
class WakeHandlers @CompositionOnly constructor(
    /**
     * The operating system woke the app for a [WakeId]. The adapter keeps the [app.snapsync.inbound.Running] it gets
     * back and expires it when the operating system says the wake's time is up — also when that came before `handle`
     * returned; the completion is answered after the wake's work, or once its cancellation has unwound.
     */
    val onWake: Granted<WakeId>,
)
