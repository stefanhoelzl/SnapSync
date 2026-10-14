package app.snapsync.data.service

import app.snapsync.data.backend.Observers
import app.snapsync.data.backend.VersionObserver
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.trust.BackendAnswer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The cell the joined screen's service line reads: the latest answer's condition — a failing answer sets it, any answer
 * from a working service clears it, and an opening forgets it.
 */
class ServiceConditionsTest {
    @Test
    fun the_latest_answer_decides_and_a_working_service_clears_it() = runTest {
        val conditions = ServiceConditions()
        assertNull(conditions.fault.value, "nothing asked yet: nothing failing")

        conditions.observe(BackendAnswer.Updating)
        assertEquals(ServiceFault.UPDATING, conditions.fault.value)
        conditions.observe(BackendAnswer.ServerIssue)
        assertEquals(ServiceFault.TROUBLE, conditions.fault.value)
        conditions.observe(BackendAnswer.Refused)
        assertNull(conditions.fault.value, "a refusal is an answer from a working service")
    }

    @Test
    fun an_opening_forgets_a_failure_from_before_it() = runTest {
        val conditions = ServiceConditions()
        conditions.observe(BackendAnswer.NoAnswer)
        conditions.opening()
        assertNull(conditions.fault.value)
    }

    @Test
    fun every_observer_is_shown_every_answer_in_order() = runTest {
        val seen = mutableListOf<String>()
        fun named(name: String) = object : VersionObserver {
            override suspend fun observe(answer: BackendAnswer) {
                seen += "$name:$answer"
            }
        }
        Observers(named("a"), named("b")).observe(BackendAnswer.Served)
        assertEquals(listOf("a:Served", "b:Served"), seen)
    }
}
