package app.snapsync.gallery

import app.snapsync.ports.AssetFilter
import platform.Foundation.timeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The **filter → `PHFetchOptions` translation**. Runs on the simulator rather than the JVM because the predicate is
 * built with real `NSPredicate`.
 *
 * The filter is a hint: the predicate narrows by its date range and nothing else, and widens each bound rather than
 * narrowing it, because what it returns is also the walk's presence set.
 */
class PhotoKitPredicateTest {

    private val from = Instant.parse("2026-06-01T00:00:00Z")
    private val until = Instant.parse("2026-06-30T00:00:00Z")

    @Test
    fun `an open filter narrows nothing`() {
        assertNull(predicateFor(AssetFilter(createdFrom = null, createdUntil = null)))
    }

    @Test
    fun `the lower bound is pushed alone`() {
        val format = predicateFor(AssetFilter(createdFrom = from, createdUntil = null))!!.predicateFormat
        assertTrue(format.contains("creationDate >="), format)
        assertEquals(false, format.contains("creationDate <="), format)
    }

    @Test
    fun `both bounds are pushed`() {
        val format = predicateFor(AssetFilter(createdFrom = from, createdUntil = until))!!.predicateFormat
        assertTrue(format.contains("creationDate >=") && format.contains("creationDate <="), format)
    }

    @Test
    fun `the date bounds are widened by a day rather than narrowed`() {
        val format = predicateFor(AssetFilter(createdFrom = from, createdUntil = until))!!.predicateFormat
        // A day of slack on each side: the format carries the widened instants, not the exact bounds.
        assertEquals(false, format.contains("2026-06-01 00:00:00"), "the lower bound is widened earlier: $format")
        assertEquals(false, format.contains("2026-06-30 00:00:00"), "the upper bound is widened later: $format")
    }

    @Test
    fun `an instant becomes the same NSDate`() {
        assertEquals(from.epochSeconds.toDouble(), from.toNSDate().timeIntervalSince1970)
        assertEquals(
            Instant.parse("2026-06-01T00:00:00.5Z").epochSeconds + 0.5,
            Instant.parse("2026-06-01T00:00:00.5Z").toNSDate().timeIntervalSince1970,
        )
    }
}
