@file:OptIn(EntryPointCall::class)

package app.snapsync.application.process

import app.snapsync.application.EntryPointCall
import app.snapsync.data.foreground.AppForeground
import app.snapsync.data.process.FootprintReading
import app.snapsync.data.process.FootprintTrail
import app.snapsync.domain.process.MemoryFootprint
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryFiles
import app.snapsync.ports.ProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The app's memory footprint is read into the trail only in the BACKGROUND (capability `privacy-security`): a
 * foreground reading would push the last pre-suspension one out of the trail, and a platform with no reading records
 * nothing. Observed through the trail the next process-metric report carries, over the honest in-memory files.
 */
class RecordFootprintTest {

    private val privateFiles = mutableMapOf<String, ByteArray>()
    private val trail = FootprintTrail(inMemoryFiles(private = privateFiles), fixedClock(Instant.fromEpochSeconds(0)))

    /** A [ProcessInfo] reading [footprint] as its own memory accounting. */
    private class Reading(private val footprint: MemoryFootprint?) : ProcessInfo {
        var reads = 0
        override fun memoryFootprint(): MemoryFootprint? = footprint.also { reads++ }
    }

    private val backgrounded = AppForeground(fixedClock(Instant.fromEpochSeconds(0)))
    private val foregrounded = AppForeground(fixedClock(Instant.fromEpochSeconds(0))).apply { entered() }

    @Test
    fun `a backgrounded app records its footprint at the moment`() {
        val reading = FootprintReading(Reading(MemoryFootprint(footprintBytes = 64L shl 20)))
        RecordFootprint(backgrounded, reading, trail)("push")
        val fields = trail.fields()
        assertTrue(fields.isNotEmpty(), "the reading is in the trail")
        assertTrue(fields.values.any { "push" in it }, "and names its moment: $fields")
    }

    @Test
    fun `a foregrounded app records nothing and reads nothing`() {
        val info = Reading(MemoryFootprint(footprintBytes = 64L shl 20))
        RecordFootprint(foregrounded, FootprintReading(info), trail)("push")
        assertEquals(0, info.reads)
        assertEquals(emptyMap(), trail.fields())
        assertTrue(privateFiles.isEmpty())
    }

    @Test
    fun `a platform with no footprint reading records nothing`() {
        val info = Reading(null)
        RecordFootprint(backgrounded, FootprintReading(info), trail)("push")
        assertEquals(1, info.reads)
        assertEquals(emptyMap(), trail.fields())
        assertTrue(privateFiles.isEmpty())
    }

    @Test
    fun `a reading the private area cannot keep is lost without failing the moment`() {
        val unkept = FootprintTrail(inMemoryFiles(private = null), fixedClock(Instant.fromEpochSeconds(0)))
        val reading = FootprintReading(Reading(MemoryFootprint(footprintBytes = 64L shl 20)))
        RecordFootprint(backgrounded, reading, unkept)("push")
        assertEquals(emptyMap(), unkept.fields())
    }
}
