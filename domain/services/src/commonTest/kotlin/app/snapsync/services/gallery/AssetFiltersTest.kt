package app.snapsync.services.gallery

import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.format.TimestampText
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * What a selection policy asks the gallery for: its capture range and nothing else, as a hint the platform narrows
 * by. Every other rule is applied over what comes back.
 */
class AssetFiltersTest {

    @Test
    fun `the filter is the policy's capture range alone`() {
        val filter = SelectionPolicy(
            listOf(
                SelectionRule.CaptureAfter(CaptureCutoff(TimestampText.parse("2026-06-01T00:00:00Z"))),
                SelectionRule.CaptureBefore(CaptureCeiling(TimestampText.parse("2026-06-30T00:00:00Z"))),
                SelectionRule.ExcludeScreenshots,
                SelectionRule.ExcludeScreenRecordings,
            ),
        ).assetFilter()!!
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), filter.createdFrom)
        assertEquals(Instant.parse("2026-06-30T00:00:00Z"), filter.createdUntil)
    }

    @Test
    fun `of two bounds the narrower is the filter's`() {
        val filter = SelectionPolicy(
            listOf(
                SelectionRule.CaptureAfter(CaptureCutoff(TimestampText.parse("2026-06-01T00:00:00Z"))),
                SelectionRule.CaptureAfter(CaptureCutoff(TimestampText.parse("2026-06-05T00:00:00Z"))),
                SelectionRule.CaptureBefore(CaptureCeiling(TimestampText.parse("2026-06-30T00:00:00Z"))),
                SelectionRule.CaptureBefore(CaptureCeiling(TimestampText.parse("2026-06-20T00:00:00Z"))),
            ),
        ).assetFilter()!!
        assertEquals(Instant.parse("2026-06-05T00:00:00Z"), filter.createdFrom)
        assertEquals(Instant.parse("2026-06-20T00:00:00Z"), filter.createdUntil)
    }

    @Test
    fun `an unbounded policy reads the whole library`() {
        val filter = SelectionPolicy(listOf(SelectionRule.ExcludeScreenshots)).assetFilter()!!
        assertNull(filter.createdFrom)
        assertNull(filter.createdUntil)
    }

    @Test
    fun `a policy that admits nothing reads nothing`() {
        assertNull(SelectionPolicy(listOf(SelectionRule.DenyAll)).assetFilter())
    }
}
