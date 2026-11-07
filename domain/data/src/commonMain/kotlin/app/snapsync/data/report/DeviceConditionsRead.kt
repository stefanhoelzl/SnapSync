package app.snapsync.data.report

import app.snapsync.data.process.FootprintReading
import app.snapsync.domain.process.MemoryFootprint
import app.snapsync.domain.report.DeviceConditionsReading
import app.snapsync.ports.Clock
import app.snapsync.ports.DeviceConditions

/**
 * The device's surroundings a bug report names, read now: its power, battery, thermal state and background allowance
 * ([DeviceConditions], taken only for a report), its time zone, and this process's memory footprint. It decides
 * nothing: how long a read may take, and how a fact is written, are the report's (`DeviceFacts`).
 */
class DeviceConditionsRead(
    private val conditions: DeviceConditions,
    private val clock: Clock,
    private val footprint: FootprintReading,
) {
    suspend fun read(): DeviceConditionsReading = conditions.read()

    /** The device's zone id, e.g. `Europe/Vienna`. */
    fun zone(): String = clock.timeZone().id

    /** This process's footprint, `null` where the platform reads none. */
    fun footprint(): MemoryFootprint? = footprint.now()
}
