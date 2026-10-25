package app.snapsync.feature.status

import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LedgerCountsSourceTest {

    @Test
    fun refresh_publishes_the_read_counts() = runTest {
        var next = LedgerCounts(
            done = setOf(testAssetId("d1"), testAssetId("d2"), testAssetId("d3"), testAssetId("d4")),
            pending = setOf(testAssetId("p1"), testAssetId("p2"), testAssetId("p3")),
        )
        val source = ReadingLedgerCountsSource { next }
        assertEquals(LedgerCounts.UNREAD, source.counts.value) // seeded UN-READ before any refresh
        source.refresh()
        assertEquals(
            LedgerCounts(
                done = setOf(testAssetId("d1"), testAssetId("d2"), testAssetId("d3"), testAssetId("d4")),
                pending = setOf(testAssetId("p1"), testAssetId("p2"), testAssetId("p3")),
            ),
            source.counts.value,
        )
        next = LedgerCounts(done = setOf(testAssetId("d1"), testAssetId("d2"), testAssetId("d3"), testAssetId("d4"), testAssetId("d5"), testAssetId("d6")), pending = emptySet())
        source.refresh()
        assertEquals(
            LedgerCounts(
                done = setOf(
                    testAssetId("d1"),
                    testAssetId("d2"),
                    testAssetId("d3"),
                    testAssetId("d4"),
                    testAssetId("d5"),
                    testAssetId("d6"),
                ),
                pending = emptySet(),
            ),
            source.counts.value,
        )
    }

    @Test
    fun of_splits_asset_progress_into_disjoint_done_and_pending_sets() {
        val counts = LedgerCounts.of(
            mapOf(testAssetId("a") to true, testAssetId("b") to false, testAssetId("c") to true),
        )
        assertEquals(setOf(testAssetId("a"), testAssetId("c")), counts.done)
        assertEquals(setOf(testAssetId("b")), counts.pending)
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
            if (fail) {
                error(
                    "gone",
                )
            } else {
                LedgerCounts(
                    done = setOf(
                        testAssetId("d1"),
                        testAssetId("d2"),
                        testAssetId("d3"),
                        testAssetId("d4"),
                        testAssetId("d5"),
                    ),
                    pending = setOf(testAssetId("p1"), testAssetId("p2")),
                )
            }
        }
        source.refresh()
        assertEquals(
            LedgerCounts(
                done = setOf(
                    testAssetId("d1"),
                    testAssetId("d2"),
                    testAssetId("d3"),
                    testAssetId("d4"),
                    testAssetId("d5"),
                ),
                pending = setOf(testAssetId("p1"), testAssetId("p2")),
            ),
            source.counts.value,
        )
        fail = true
        source.refresh()
        // A transient read error must NOT drop the done set to empty and flip the screen out of "In sync".
        assertEquals(
            LedgerCounts(
                done = setOf(
                    testAssetId("d1"),
                    testAssetId("d2"),
                    testAssetId("d3"),
                    testAssetId("d4"),
                    testAssetId("d5"),
                ),
                pending = setOf(testAssetId("p1"), testAssetId("p2")),
            ),
            source.counts.value,
        )
    }

    @Test
    fun a_settable_source_holds_what_it_was_told_and_a_refresh_reads_nothing_over_it() = runTest {
        val source = MutableLedgerCountsSource()
        source.set(done = setOf(testAssetId("d1")), pending = setOf(testAssetId("p1")))
        source.refresh()
        assertEquals(
            LedgerCounts(done = setOf(testAssetId("d1")), pending = setOf(testAssetId("p1"))),
            source.counts.value,
        )
        assertTrue(source.counts.value.read, "a stated value is a read one")
    }
}
