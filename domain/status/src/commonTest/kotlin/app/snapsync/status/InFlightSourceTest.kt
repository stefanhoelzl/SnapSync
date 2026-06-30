package app.snapsync.status

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InFlightSourceTest {

    @Test
    fun refresh_publishes_the_read_count() = runTest {
        var next = 3
        val source = ReadingInFlightSource { next }
        assertEquals(0, source.inFlight.value) // seeded 0 before any refresh
        source.refresh()
        assertEquals(3, source.inFlight.value)
        next = 1
        source.refresh()
        assertEquals(1, source.inFlight.value)
    }

    @Test
    fun a_failed_read_yields_zero_without_throwing() = runTest {
        val source = ReadingInFlightSource { error("ledger unreadable") }
        source.refresh() // must not throw
        assertEquals(0, source.inFlight.value)
    }

    @Test
    fun a_failed_read_after_a_good_value_falls_back_to_zero() = runTest {
        var fail = false
        val source = ReadingInFlightSource { if (fail) error("gone") else 5 }
        source.refresh()
        assertEquals(5, source.inFlight.value)
        fail = true
        source.refresh()
        assertEquals(0, source.inFlight.value) // failure resets to 0
    }
}
