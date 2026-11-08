package app.snapsync.domain.process

import app.snapsync.domain.foreground.InFront
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The app's own memory readings, as they are kept and as a process-metric report carries them. */
class FootprintTrailRuleTest {

    private fun sample(second: Long, moment: String = "after onSilentPush", bytes: Long = 83_574_000) =
        FootprintSample(
            Instant.fromEpochSeconds(second),
            moment,
            MemoryFootprint(bytes, peakBytes = 120_000_000, headroomBytes = 1_200_000_000),
        )

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
        val trail = (1L..12L).fold(emptyList<FootprintSample>()) { kept, second ->
            FootprintTrailRule.appended(kept, sample(second))
        }
        assertEquals(FootprintTrailRule.LENGTH, trail.size)
        assertEquals((5L..12L).map { it * 1_000 }, trail.map { it.atEpochMillis })
    }

    @Test
    fun `the report's context names the newest reading first`() {
        val fields = FootprintTrailRule.fields(
            listOf(sample(1, "entering background"), sample(2, "after onSilentPush")),
        )
        assertEquals(setOf("ownFootprint.0", "ownFootprint.1"), fields.keys)
        assertTrue("after onSilentPush" in fields.getValue("ownFootprint.0"), "the last reading taken: $fields")
        assertTrue("entering background" in fields.getValue("ownFootprint.1"), fields.toString())
    }

    @Test
    fun `no reading key collides with what the rule reads, so a trail alone never crosses`() {
        val fields = FootprintTrailRule.fields(listOf(sample(1)))
        assertEquals(emptyList(), ProcessMetricRules.account(ProcessMetricReport(fields)).crossings)
    }

    @Test
    fun `only a reading away from the front is kept`() {
        assertTrue(FootprintTrailRule.keeps(InFront.AWAY))
        assertFalse(FootprintTrailRule.keeps(InFront.IN_FRONT))
    }
}
