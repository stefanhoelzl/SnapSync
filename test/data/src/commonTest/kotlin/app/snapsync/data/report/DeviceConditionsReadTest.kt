package app.snapsync.data.report

import app.snapsync.data.process.FootprintReading
import app.snapsync.domain.process.MemoryFootprint
import app.snapsync.domain.report.Fact
import app.snapsync.mock.DeviceConditionsMock
import app.snapsync.mock.ProcessInfoMock
import app.snapsync.mock.fixedClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The device's surroundings a bug report names: the platform's reading, the zone and the footprint, as read. */
class DeviceConditionsReadTest {
    private val conditions = DeviceConditionsMock()
    private val process = ProcessInfoMock()
    private val read = DeviceConditionsRead(
        conditions.port(),
        fixedClock(Instant.parse("2026-07-06T12:00:00Z"), TimeZone.of("Europe/Vienna")),
        FootprintReading(process.port()),
    )

    @Test
    fun `the platform's reading is handed over as it answered`() = runTest {
        conditions.reading = DeviceConditionsMock.TYPICAL.copy(powerSaving = Fact.Known(true))
        assertEquals(conditions.reading, read.read())
    }

    @Test
    fun `the zone is the clock's - the footprint the process's, none where the platform reads none`() {
        assertEquals("Europe/Vienna", read.zone())
        process.memory = MemoryFootprint(footprintBytes = 42)
        assertEquals(MemoryFootprint(footprintBytes = 42), read.footprint())
        process.memory = null
        assertEquals(null, read.footprint())
    }
}
