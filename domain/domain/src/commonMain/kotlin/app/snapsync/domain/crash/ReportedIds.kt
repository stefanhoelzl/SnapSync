package app.snapsync.domain.crash

import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.report.Fact

/**
 * The ids SnapSync itself knows, as every report carries them in its own section ([CrashSections.SNAPSYNC]) — apart
 * from the SDK's, whose `event_id` is the report's own: the [event] the device is in (`null` while none is), the
 * [device]'s id as it could be read (never minted for a report), and the [process] that reports. In full: they are what
 * SnapSync already exchanges with its own service, and what the operator finds a failure's event and device by.
 */
class ReportedIds(val event: EventId?, val device: Fact<DeviceId>, val process: String) {
    /** The section's fields: `event` (`none` while in none), `device` (or why it could not be read), `process`. */
    fun section(): Map<String, String> = buildMap {
        put("event", if (event != null) event.value else "none")
        device(device)?.let { put("device", it) }
        put("process", process)
    }

    private fun device(fact: Fact<DeviceId>): String? = when (fact) {
        is Fact.Known -> fact.value.value
        Fact.Unsupported -> null
        is Fact.Failed -> fact.shown
    }
}
