package app.snapsync.domain.report

import kotlin.test.Test
import kotlin.test.assertEquals

/** How a bug report names each of the device's conditions — the labels its state section carries. */
class DeviceConditionsTest {
    @Test
    fun `every condition is named as the report writes it`() {
        assertEquals(listOf("available", "denied", "restricted"), BackgroundRefresh.entries.map { it.label })
        assertEquals(
            listOf("active", "working_set", "frequent", "rare", "restricted"),
            StandbyBucket.entries.map { it.label },
        )
        assertEquals(listOf("charging", "full", "not_charging", "unplugged"), Charging.entries.map { it.label })
        assertEquals(listOf("nominal", "fair", "serious", "critical"), Thermal.entries.map { it.label })
    }
}
