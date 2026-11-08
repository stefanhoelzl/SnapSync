package app.snapsync.composition.app

import app.snapsync.application.wake.RearmHeartbeat
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.upload.OsUploader
import app.snapsync.data.wake.WakeArming

/** The wake's layers (`docs/patterns.md`): the wakes the app asks for, and the re-arm every wake ends with. */
class WakeGraph(ports: AppPorts, services: ServicesGraph, grant: GrantRead, osUploader: OsUploader) {
    val arming: WakeArming = WakeArming(ports.wake)
    val rearm: RearmHeartbeat =
        RearmHeartbeat(arming, services.membership, services.wallClock, grant, osUploader, services.mobileData)
}
