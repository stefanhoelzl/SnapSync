package app.snapsync.protection

import app.snapsync.model.Availability
import app.snapsync.ports.ProcessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication

/**
 * The iOS [ProcessInfo] of the app process: `UIApplication.isProtectedDataAvailable`. App-only, because
 * `UIApplication` is unavailable to app extensions — the extension records the status of each protected read it
 * makes instead (capability `sync-status`, "Background entry points record protected-data state").
 *
 * `UIApplication` is main-thread-only and the entry points that ask run on the composition lane (law "Dispatcher
 * lanes are fixed by the composition"), so the read names the main lane itself. It is a property read, not work:
 * nothing blocking follows it onto main.
 *
 * WHAT IS CONTRACTED, AND WHAT CANNOT BE. `ProcessInfoContract` runs live on the simulator app — the one CI host
 * with a `UIApplication` — and holds that an unlocked device reads available, through the main-lane hop above. The
 * unavailable answer has no clause and no host: no host lets a binding enter "not unlocked since boot", because the
 * simulator implements no data protection and the rig drives only a running, unlocked app (`docs/architecture.md`,
 * "Hosts are a closed set of what changes reachable states"). That belief — `isProtectedDataAvailable` is false on
 * a device locked since boot — is Apple's documented contract, and the background entry points' device-log lines
 * are the only place it is ever observed.
 */
class IosProcessInfo : ProcessInfo {
    override suspend fun protectedDataAvailable(): Availability =
        if (withContext(Dispatchers.Main) { UIApplication.sharedApplication.isProtectedDataAvailable() }) {
            Availability.AVAILABLE
        } else {
            Availability.UNAVAILABLE
        }
}
