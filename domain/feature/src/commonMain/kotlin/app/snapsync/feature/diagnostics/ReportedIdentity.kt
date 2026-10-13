package app.snapsync.feature.diagnostics

import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.model.Fact

/** The device id as a report writes it: the id, or why there is none. Never a mint — see `MintingDeviceIdentity.current`. */
fun DeviceIdRead.asFact(): Fact<String> = when (this) {
    is DeviceIdRead.Ok -> Fact.Known(value.value)
    is DeviceIdRead.Unavailable -> Fact.Failed("the secure store could not be read: $detail")
    DeviceIdRead.Absent -> Fact.Failed("no device id stored yet")
}
