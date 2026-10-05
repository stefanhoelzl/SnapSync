package app.snapsync.services.device

import app.snapsync.model.DeviceConditionsReading
import app.snapsync.ports.DeviceConditions

/**
 * The device's power, battery, thermal and background-allowance state (capability `privacy-security`) — the
 * [DeviceConditions] port, for the bug report's feature, which may not see a port (`docs/architecture.md`, "a feature
 * sees services, never ports"). It decides nothing: how long a read may take and how a fact is written are
 * `CollectDiagnosticDump`'s.
 */
class DeviceConditionsReadings(private val conditions: DeviceConditions) {
    suspend fun read(): DeviceConditionsReading = conditions.read()
}
