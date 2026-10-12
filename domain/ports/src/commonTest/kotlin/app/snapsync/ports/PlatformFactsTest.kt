package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals

/** The small facts the formatting and metrics ports hand over, held as the platform stated them. */
class PlatformFactsTest {

    @Test
    fun `a device locale and a metric leaf hold what the platform stated`() {
        val locale = DeviceLocale("de", null)
        assertEquals("de" to null, locale.language to locale.region)
        val payload = ProcessMetricPayload(MetricNode.Leaf("749 ms"))
        assertEquals("749 ms", (payload.root as MetricNode.Leaf).text)
    }
}
