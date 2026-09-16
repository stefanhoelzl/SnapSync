package app.snapsync.feature.upload

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoteRequestedTest {

    @Test
    fun succeeds_on_the_first_attempt() = runTest {
        var calls = 0
        val ok = demoteRequestedOffMain(demote = { calls++ }, dispatcher = Dispatchers.Unconfined)
        assertTrue(ok)
        assertEquals(1, calls, "no retry needed when the first demote succeeds")
    }

    @Test
    fun retries_a_transient_failure_then_succeeds() = runTest {
        var calls = 0
        val ok = demoteRequestedOffMain(
            demote = { calls++; if (calls < 3) throw RuntimeException("SQLITE_BUSY") },
            attempts = 3,
            dispatcher = Dispatchers.Unconfined,
        )
        assertTrue(ok, "a transient failure is retried within the bound")
        assertEquals(3, calls)
    }

    @Test
    fun gives_up_after_the_bound_without_throwing() = runTest {
        var calls = 0
        val ok = demoteRequestedOffMain(
            demote = { calls++; throw RuntimeException("still failing") },
            attempts = 3,
            dispatcher = Dispatchers.Unconfined,
        )
        assertFalse(ok, "returns false rather than throwing — best-effort")
        assertEquals(3, calls, "bounded: exactly `attempts` tries, no more")
    }
}
