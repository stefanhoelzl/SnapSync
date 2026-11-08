package app.snapsync.domain.asset

import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId

/**
 * The resource a sealed file is: the event it belongs to, the device that shared it, its asset and its role. A sealed
 * file is bound to it, so a file moved to another event, device, asset or role does not open.
 */
class FileBinding(val event: EventId, val device: DeviceId, val asset: AssetId, val role: ResourceRole) {
    override fun toString(): String = "FileBinding($event/$device/$asset/$role)"
}
