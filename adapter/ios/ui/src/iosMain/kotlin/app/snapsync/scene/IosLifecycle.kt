package app.snapsync.scene

import app.snapsync.logging.invocation
import app.snapsync.model.PlatformEntry
import app.snapsync.objc.objcBoundary
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.LifecycleHandlers
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification

/**
 * The iOS [Lifecycle]: `UIApplication`'s `didBecomeActive` ↔ the app became active, `willResignActive` ↔ it is leaving
 * the active state — including the transient `.inactive` cases (the app switcher, an incoming call, a permission
 * prompt), which the foreground flow treats as leaving. `willEnterForeground` would lose the cold launch's first
 * activation, so it is not used. Observed from Kotlin, never from SwiftUI's `scenePhase`, whose split was a Swift `if`.
 *
 * [listen] installs the two observers — process-lifetime, never removed; a background launch installs them too and
 * simply never sees `didBecomeActive`. Each activation records [SceneRecord.everActive] **before** the handler runs,
 * so the scene rule already knows the app has been active when the handler's work builds a screen.
 *
 * The scene delegate's other callbacks carry no decision and no handler: they reach [deliverSceneEvent], which logs
 * them (capability `privacy-security`) — the record of which of UIKit's paths ran is what a delivery investigation
 * reads.
 */
class IosLifecycle(private val record: SceneRecord, private val log: Logger) : Lifecycle {
    private var handlers: LifecycleHandlers? = null

    override fun listen(handlers: LifecycleHandlers) {
        this.handlers = handlers
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { objcBoundary(log, "didBecomeActive") { deliverForeground() } },
        )
        center.addObserverForName(
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { objcBoundary(log, "willResignActive") { deliverBackground() } },
        )
    }

    /** The app became active: recorded first, then the handler — what `didBecomeActive` delivers. */
    @PlatformEntry
    fun deliverForeground() {
        record.markActive()
        handlers?.onForeground()
    }

    /** The app is leaving the active state — what `willResignActive` delivers. */
    @PlatformEntry
    fun deliverBackground() {
        handlers?.onBackground()
    }

    /** A scene-delegate callback that only records that it ran, with what the platform handed it. */
    fun deliverSceneEvent(name: String, params: String = "", severity: Severity = Severity.Info) =
        log.invocation(name, params = params, severity = severity) { }
}
