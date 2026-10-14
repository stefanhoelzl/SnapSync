package app.snapsync.services.crash

import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryFiles
import app.snapsync.model.Availability
import app.snapsync.model.MemoryFootprint
import app.snapsync.ports.ProcessInfo
import app.snapsync.services.foreground.AppForeground
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The app's memory footprint is read into the trail only in the BACKGROUND (capability `privacy-security`): a
 * foreground reading would push the last pre-suspension one out of the trail, and a platform with no reading records
 * nothing. Observed through the trail the next process-metric report carries, over the honest in-memory files.
 */
class FootprintSamplerTest {

    private val privateFiles = mutableMapOf<String, ByteArray>()
    private val trail = FootprintTrail(inMemoryFiles(private = privateFiles), fixedClock(Instant.fromEpochSeconds(0)))

    /** A [ProcessInfo] reading [footprint] as its own memory accounting. */
    private class Reading(private val footprint: MemoryFootprint?) : ProcessInfo {
        var reads = 0
        override suspend fun protectedDataAvailable() = Availability.AVAILABLE
        override fun memoryFootprint(): MemoryFootprint? = footprint.also { reads++ }
    }

    private val backgrounded = AppForeground(fixedClock(Instant.fromEpochSeconds(0)), onEntered = {})
    private val foregrounded = AppForeground(
        fixedClock(Instant.fromEpochSeconds(0)),
        onEntered = {},
    ).apply { entered() }

    @Test
    fun `a backgrounded app records its footprint at the moment`() {
        FootprintSampler(Reading(MemoryFootprint(footprintBytes = 64L shl 20)), trail, backgrounded).record("push")
        val fields = trail.fields()
        assertTrue(fields.isNotEmpty(), "the reading is in the trail")
        assertTrue(fields.values.any { "push" in it }, "and names its moment: $fields")
    }

    @Test
    fun `a foregrounded app records nothing and reads nothing`() {
        val info = Reading(MemoryFootprint(footprintBytes = 64L shl 20))
        FootprintSampler(info, trail, foregrounded).record("push")
        assertEquals(0, info.reads)
        assertEquals(emptyMap(), trail.fields())
        assertTrue(privateFiles.isEmpty())
    }

    @Test
    fun `a platform with no footprint reading records nothing`() {
        val info = Reading(null)
        FootprintSampler(info, trail, backgrounded).record("push")
        assertEquals(1, info.reads)
        assertEquals(emptyMap(), trail.fields())
        assertTrue(privateFiles.isEmpty())
    }

    @Test
    fun `a reading the private area cannot keep is lost without failing the moment`() {
        val unkept = FootprintTrail(inMemoryFiles(private = null), fixedClock(Instant.fromEpochSeconds(0)))
        FootprintSampler(Reading(MemoryFootprint(footprintBytes = 64L shl 20)), unkept, backgrounded).record("push")
        assertEquals(emptyMap(), unkept.fields())
    }
}
