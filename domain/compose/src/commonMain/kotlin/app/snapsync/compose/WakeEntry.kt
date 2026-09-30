package app.snapsync.compose

import app.snapsync.model.WakeId
import app.snapsync.ports.Completion
import app.snapsync.ports.WakeHandlers
import app.snapsync.model.invocation
import app.snapsync.services.wake.OsCompletions
import kotlinx.coroutines.launch

/**
 * What the `Wake` port tells [core] — registered by the host zone's `listen`, as the graph is composed at launch
 * (`SnapSyncRoot.onLaunch` forces it: iOS requires the `BGTask` launch handler before the app finishes launching).
 *
 * Every wake the app asks for is the heartbeat's (capability `background-upload`, "The tail runner reimplements the OS
 * scheduler"): whichever [WakeId] fires, busy or idle, its work is the full tail, then the end-of-wake step — which asks
 * the event for others' photos and, after its end, for its state, each at most once an hour (decision record
 * `changes/timely-background-receiving`, D4–D5). It has no own work beyond the prelude — the wake is a grant of time — so
 * its completion is held until that tail ends, or released at once on the operating system's expiry
 * ([Completion.onExpired]), which also stops the tail. The re-arm is the tail runner's.
 */
internal fun wakeHandlersOf(core: AppCore): WakeHandlers =
    WakeHandlers(onWake = { id, completion -> core.onWake(id, completion) })

private fun AppCore.onWake(id: WakeId, completion: Completion) =
    services.log.invocation(process.entryContext, "onWake", params = "id=$id") { runWake(id, completion) }

private fun AppCore.runWake(id: WakeId, completion: Completion) {
    val completions = OsCompletions("onWake($id)", log = services.log)
    val handover = completions.adopt(completion)
    // Registered before the launch, so an expiry the operating system fires before the coroutine first runs is not
    // lost: a registration after the expiry runs at once (the port's promise).
    completion.onExpired {
        val reason = "the operating system's expiry for the $id wake"
        tail.runner.stop(reason)
        handover.releaseOnExpiry(reason)
    }
    scope.launch {
        completions.releaseAfter {
            services.log.invocation(process.entryContext, "runWake", params = "id=$id") {
                prelude()
                // A wake whose time is already up requests no tail: a stop while none runs is a no-op. A tail that
                // fails is contained — the completion is still released, and the next wake retries.
                if (!handover.isReleased) {
                    tail.heartbeatThenFinish("$id")
                }
            }
        }
    }
}
