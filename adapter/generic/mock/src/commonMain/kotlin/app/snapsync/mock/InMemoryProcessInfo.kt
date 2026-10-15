package app.snapsync.mock

import app.snapsync.model.MemoryFootprint
import app.snapsync.ports.ProcessInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An honest in-memory [ProcessInfo]: answers the footprint the caller's cell holds, read as the app records it. */
internal class InMemoryProcessInfo(
    private val footprint: StateFlow<MemoryFootprint?> = MutableStateFlow(DEFAULT_FOOTPRINT),
) : ProcessInfo {
    override fun memoryFootprint(): MemoryFootprint? = footprint.value
}

/** A plausible small app: what a mock process reads until an operator sets otherwise. */
internal val DEFAULT_FOOTPRINT =
    MemoryFootprint(footprintBytes = 40_000_000, peakBytes = 60_000_000, headroomBytes = 1_400_000_000)
