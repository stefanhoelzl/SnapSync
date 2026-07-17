package app.snapsync.feature.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class LedgerCountsSourceTest {

    @Test
    fun refresh_publishes_the_read_counts() = runTest {
        var next = LedgerCounts(completed = 4, pending = 3)
        val source = ReadingLedgerCountsSource { next }
        assertEquals(LedgerCounts.ZERO, source.counts.value) // seeded before any refresh
        source.refresh()
        assertEquals(LedgerCounts(completed = 4, pending = 3), source.counts.value)
        next = LedgerCounts(completed = 6, pending = 0)
        source.refresh()
        assertEquals(LedgerCounts(completed = 6, pending = 0), source.counts.value)
    }

    @Test
    fun a_failed_read_before_any_value_stays_zero() = runTest {
        val source = ReadingLedgerCountsSource { error("ledger unreadable") }
        source.refresh() // must not throw
        assertEquals(LedgerCounts.ZERO, source.counts.value)
    }

    @Test
    fun a_failed_read_after_a_good_value_retains_the_last_good() = runTest {
        var fail = false
        val source = ReadingLedgerCountsSource {
            if (fail) error("gone") else LedgerCounts(completed = 5, pending = 2)
        }
        source.refresh()
        assertEquals(LedgerCounts(completed = 5, pending = 2), source.counts.value)
        fail = true
        source.refresh()
        // A transient read error must NOT drop completed to 0 and flip the screen out of "In sync".
        assertEquals(LedgerCounts(completed = 5, pending = 2), source.counts.value)
    }
}
