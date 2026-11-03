package app.snapsync.compose

import app.snapsync.application.wake.RearmHeartbeat
import app.snapsync.data.wake.WakeArming

/**
 * The wake's layers over [core] (`docs/patterns.md`): the wakes the app asks for, and the re-arm every wake ends with —
 * each built once, on first touch.
 */
internal class WakeLayers(private val core: AppCore) {
    val arming: WakeArming by lazy { WakeArming(core.ports.wake) }

    val rearm: RearmHeartbeat by lazy {
        RearmHeartbeat(
            arming,
            core.services.membership,
            core.services.wallClock,
            core.grant,
            core.osUploader,
            core.services.mobileData,
        )
    }
}
