package app.snapsync.ios

import app.snapsync.keychain.ProtectedDataAvailability
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationProtectedDataDidBecomeAvailable

/**
 * The iOS [ProtectedDataAvailability] adapter (capability `ios-app-shell`): iOS reports directly
 * whether the Keychain and the app/App-Group containers are readable right now, and posts a
 * notification the instant they become readable (i.e. when the user unlocks).
 *
 * Wiring only — the decision (run now / defer / resume) is `ProtectedDataGate` in `:domain:keychain`,
 * where it is tested. This file exists in `:app:ios` and **not** in a shared module on purpose:
 * `UIApplication` is unavailable to app extensions, and a shared module would link it into the upload
 * extension's framework as well.
 *
 * The observer is never removed: it is process-lifetime, like the composition root that owns it.
 */
class IosProtectedData : ProtectedDataAvailability {

    override fun isAvailable(): Boolean = UIApplication.sharedApplication.isProtectedDataAvailable()

    override fun onBecameAvailable(listener: () -> Unit) {
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationProtectedDataDidBecomeAvailable,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { listener() },
        )
    }
}
