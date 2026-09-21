package app.snapsync.feature.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LedgerCountsSourceTest {

    @Test
    fun refresh_publishes_the_read_counts() = runTest {
        var next = LedgerCounts(done = setOf("d1", "d2", "d3", "d4"), pending = setOf("p1", "p2", "p3"))
        val source = ReadingLedgerCountsSource { next }
        assertEquals(LedgerCounts.UNREAD, source.counts.value) // seeded UN-READ before any refresh
        source.refresh()
        assertEquals(LedgerCounts(done = setOf("d1", "d2", "d3", "d4"), pending = setOf("p1", "p2", "p3")), source.counts.value)
        next = LedgerCounts(done = setOf("d1", "d2", "d3", "d4", "d5", "d6"), pending = emptySet())
        source.refresh()
        assertEquals(LedgerCounts(done = setOf("d1", "d2", "d3", "d4", "d5", "d6"), pending = emptySet()), source.counts.value)
    }

    @Test
    fun of_splits_asset_progress_into_disjoint_done_and_pending_sets() {
        val counts = LedgerCounts.of(mapOf("a" to true, "b" to false, "c" to true))
        assertEquals(setOf("a", "c"), counts.done)
        assertEquals(setOf("b"), counts.pending)
        assertTrue(counts.read)
        assertEquals(LedgerCounts.ZERO, LedgerCounts.of(emptyMap()))
    }

    @Test
    fun a_failed_read_before_any_value_stays_un_read() = runTest {
        val source = ReadingLedgerCountsSource { error("ledger unreadable") }
        source.refresh() // must not throw
        // UN-READ, not a read zero: a failure that promoted the seed to "we looked and found nothing"
        // would let the status projection settle to "In sync" over counts nobody took.
        assertEquals(LedgerCounts.UNREAD, source.counts.value)
        assertFalse(source.counts.value.read)
    }

    @Test
    fun a_read_empty_ledger_is_a_real_answer_not_the_seed() = runTest {
        val source = ReadingLedgerCountsSource { LedgerCounts(done = emptySet(), pending = emptySet()) }
        source.refresh()
        // Same numbers as UNREAD, different answer: this one was read, so it mints a snapshot.
        assertTrue(source.counts.value.read)
        assertNotEquals(LedgerCounts.UNREAD, source.counts.value)
    }

    @Test
    fun a_failed_read_after_a_good_value_retains_the_last_good() = runTest {
        var fail = false
        val source = ReadingLedgerCountsSource {
            if (fail) error("gone") else LedgerCounts(done = setOf("d1", "d2", "d3", "d4", "d5"), pending = setOf("p1", "p2"))
        }
        source.refresh()
        assertEquals(LedgerCounts(done = setOf("d1", "d2", "d3", "d4", "d5"), pending = setOf("p1", "p2")), source.counts.value)
        fail = true
        source.refresh()
        // A transient read error must NOT drop the done set to empty and flip the screen out of "In sync".
        assertEquals(LedgerCounts(done = setOf("d1", "d2", "d3", "d4", "d5"), pending = setOf("p1", "p2")), source.counts.value)
    }
}
