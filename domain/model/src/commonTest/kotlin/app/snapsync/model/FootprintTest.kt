package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The app's own memory readings, as they are kept and as a process-metric report carries them. */
class FootprintTest {

    private fun sample(second: Long, moment: String = "after onSilentPush", bytes: Long = 83_574_000) =
        FootprintSample(Instant.fromEpochSeconds(second), moment, MemoryFootprint(bytes, peakBytes = 120_000_000, headroomBytes = 1_200_000_000))

    @Test
    fun `a reading reads in kB - the unit the platform's report states its memory in`() {
        assertEquals(
            "1970-01-01T00:00:10Z entering background: footprint 83574 kB, peak 120000 kB, headroom 1200000 kB",
            sample(10, "entering background").describe(),
        )
    }

    @Test
    fun `what the platform did not say is left out rather than read as zero`() {
        val bare = FootprintSample(Instant.fromEpochSeconds(0), "after runWake(heartbeat)", MemoryFootprint(5_000))
        assertEquals("1970-01-01T00:00:00Z after runWake(heartbeat): footprint 5 kB", bare.describe())
    }

    @Test
    fun `the trail keeps the newest readings and drops the oldest`() {
        val trail = (1L..12L).fold(emptyList<FootprintSample>()) { kept, second -> appendedFootprint(kept, sample(second)) }
        assertEquals(FOOTPRINT_TRAIL_LENGTH, trail.size)
        assertEquals((5L..12L).map { it * 1_000 }, trail.map { it.atEpochMillis })
    }

    @Test
    fun `the report's context names the newest reading first`() {
        val fields = footprintFields(listOf(sample(1, "entering background"), sample(2, "after onSilentPush")))
        assertEquals(setOf("$FOOTPRINT_FIELD_PREFIX.0", "$FOOTPRINT_FIELD_PREFIX.1"), fields.keys)
        assertTrue("after onSilentPush" in fields.getValue("$FOOTPRINT_FIELD_PREFIX.0"), "the last reading taken: $fields")
        assertTrue("entering background" in fields.getValue("$FOOTPRINT_FIELD_PREFIX.1"), fields.toString())
    }

    @Test
    fun `a trail round-trips through its file`() {
        val trail = listOf(sample(1), FootprintSample(Instant.fromEpochSeconds(2), "x", MemoryFootprint(1)))
        assertEquals(trail, decodeFootprintTrail(encodeFootprintTrail(trail)))
    }

    @Test
    fun `a file that holds no trail reads as none and stands in nothing's way`() {
        assertEquals(emptyList<FootprintSample>(), decodeFootprintTrail("not json"))
        assertEquals(emptyMap<String, String>(), footprintFields(decodeFootprintTrail("")))
    }

    @Test
    fun `no reading key collides with what the rule reads`() {
        val fields = footprintFields(listOf(sample(1)))
        assertTrue(fields.keys.none { it.startsWith(PROCESS_EXIT_PREFIX) || it.startsWith(HANG_HISTOGRAM_PREFIX) })
        assertEquals(1, processMetricEmissions(ProcessMetricReport(fields)).size, "a trail alone never crosses")
    }
}
