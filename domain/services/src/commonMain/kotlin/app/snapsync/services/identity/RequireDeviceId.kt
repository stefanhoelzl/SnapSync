package app.snapsync.services.identity

import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.device.DeviceId
import app.snapsync.model.DeviceIdentityAbsent
import app.snapsync.model.SecureStoreUnavailable

/**
 * MIGRATING (`PENDING_CLEANUPS.md`, "The old zones' throwing device-id read"): the device id as the old zones read it —
 * thrown out of the step that asked when there is none, as before trust moved into its layers. Each concept's phase
 * moves its callers onto [DeviceIdentity.id]'s sealed read and deletes their uses; the last one deletes this.
 */
fun DeviceIdentity.requireDeviceId(): DeviceId = when (val read = id()) {
    is DeviceIdRead.Ok -> read.value
    is DeviceIdRead.Unavailable -> throw SecureStoreUnavailable(read.detail)
    DeviceIdRead.Absent -> throw DeviceIdentityAbsent()
}
