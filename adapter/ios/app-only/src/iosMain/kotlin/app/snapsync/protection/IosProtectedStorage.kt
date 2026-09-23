package app.snapsync.protection

import app.snapsync.ports.ProtectedStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication

/**
 * The iOS [ProtectedStorage]: `UIApplication.isProtectedDataAvailable`. App-only, because `UIApplication` is
 * unavailable to app extensions — the extension records the status of each protected read it makes instead
 * (capability `ios-app-shell`, "Background entry points record protected-data state").
 *
 * `UIApplication` is main-thread-only and the entry points that ask run on the composition lane (law "Dispatcher
 * lanes are fixed by the composition"), so the read names the main lane itself. It is a property read, not work:
 * nothing blocking follows it onto main.
 *
 * WHAT IS CONTRACTED, AND WHAT CANNOT BE. `ProtectedStorageContract` runs live on the simulator app — the one CI
 * host with a `UIApplication` — and holds that an unlocked device reads readable, through the main-lane hop
 * above. The `false` answer has no clause and no host: no host lets a binding enter "not unlocked since boot",
 * because the simulator implements no data protection and the rig drives only a running, unlocked app
 * (capability `port-contracts`, "Hosts are a closed set of what changes reachable states"). That belief —
 * `isProtectedDataAvailable` is false on a device locked since boot — is Apple's documented contract, and the
 * background entry points' device-log lines are the only place it is ever observed.
 */
class IosProtectedStorage : ProtectedStorage {
    override suspend fun readable(): Boolean =
        withContext(Dispatchers.Main) { UIApplication.sharedApplication.isProtectedDataAvailable() }
}
