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
 */
class IosProtectedStorage : ProtectedStorage {
    override suspend fun readable(): Boolean =
        withContext(Dispatchers.Main) { UIApplication.sharedApplication.isProtectedDataAvailable() }
}
