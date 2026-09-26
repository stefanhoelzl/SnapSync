package app.snapsync.fake

import app.snapsync.model.Availability
import app.snapsync.ports.ProcessInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * An honest in-memory [ProcessInfo]: answers whatever the caller's cell holds. A world models a device locked since
 * boot by setting it [Availability.UNAVAILABLE]; nothing in the core decides on the answer, so the cell only changes
 * what the background entry points record.
 */
internal class InMemoryProcessInfo(private val protectedData: StateFlow<Availability>) : ProcessInfo {
    override suspend fun protectedDataAvailable(): Availability = protectedData.value
}
