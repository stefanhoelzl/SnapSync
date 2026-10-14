package app.snapsync.domain.service

import app.snapsync.domain.trust.BackendAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServiceFaultTest {
    @Test
    fun each_failing_answer_is_its_condition() {
        assertEquals(ServiceFault.UNREACHABLE, ServiceFault.of(BackendAnswer.NoAnswer))
        assertEquals(ServiceFault.TROUBLE, ServiceFault.of(BackendAnswer.ServerIssue))
        assertEquals(ServiceFault.UPDATING, ServiceFault.of(BackendAnswer.Updating))
    }

    @Test
    fun an_answer_from_a_working_service_is_no_fault_a_refusal_included() {
        for (answer in listOf(
            BackendAnswer.Served,
            BackendAnswer.Rejected,
            BackendAnswer.Refused,
            BackendAnswer.TooOld(null),
        )) {
            assertNull(ServiceFault.of(answer), "$answer")
        }
    }
}
