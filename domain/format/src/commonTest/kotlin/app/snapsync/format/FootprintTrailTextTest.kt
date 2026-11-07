package app.snapsync.format

import app.snapsync.domain.process.FootprintSample
import kotlin.test.Test
import kotlin.test.assertEquals

/** The trail's file: written as every build since the trail began wrote it, and read back from any of them. */
class FootprintTrailTextTest {

    /** A trail exactly as the last build that kept it in `model/Footprint.kt` wrote it — its serializer's own output. */
    private val written =
        """[{"atEpochMillis":1000,"moment":"entering background","footprintBytes":83574000,"peakBytes":120000000},""" +
            """{"atEpochMillis":2000,"moment":"x","footprintBytes":1,"peakBytes":2,"headroomBytes":3},""" +
            """{"atEpochMillis":3000,"moment":"y","footprintBytes":4}]"""

    private val trail = listOf(
        FootprintSample(1_000, "entering background", 83_574_000, peakBytes = 120_000_000),
        FootprintSample(2_000, "x", 1, peakBytes = 2, headroomBytes = 3),
        FootprintSample(3_000, "y", 4),
    )

    @Test
    fun `a trail an older build wrote reads as the same readings`() = assertEquals(
        trail,
        FootprintTrailText.decode(written),
    )

    @Test
    fun `a trail is written in the very same shape, an unknown reading left out`() =
        assertEquals(written, FootprintTrailText.encode(trail))

    @Test
    fun `a file that holds no trail reads as none and stands in nothing's way`() {
        listOf(
            "not json",
            "",
            "{}",
            "[1]",
            """[{"moment":"x"}]""",
            """[{"atEpochMillis":1,"footprintBytes":3}]""",
            """[{"atEpochMillis":1,"moment":"x"}]""",
            """[{"atEpochMillis":1,"moment":{},"footprintBytes":3}]""",
            """[{"atEpochMillis":1,"moment":2,"footprintBytes":3}]""",
        )
            .forEach { assertEquals(emptyList(), FootprintTrailText.decode(it), "'$it'") }
    }

    @Test
    fun `a reading that cannot be read spoils the whole trail rather than shifting the newest`() {
        val partial = """[{"atEpochMillis":1,"moment":"x","footprintBytes":1},{"moment":"y"}]"""
        assertEquals(emptyList(), FootprintTrailText.decode(partial))
    }
}
