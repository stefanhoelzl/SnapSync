package app.snapsync.query

import app.snapsync.domain.request.RequestId
import kotlin.test.Test
import kotlin.test.assertEquals

/** A member's latest request of one kind: running, then answered — and an answer is only ever its own request's. */
class RequestStatusesTest {

    private val statuses = RequestStatuses<String>()

    @Test
    fun `a request runs until it is answered`() {
        assertEquals(RequestStatus.Idle, statuses.status.value)
        statuses.begun(RequestId(1))
        assertEquals(RequestStatus.InFlight(RequestId(1)), statuses.status.value)
        statuses.done(RequestId(1), "saved")
        assertEquals(RequestStatus.Done(RequestId(1), "saved"), statuses.status.value)
    }

    @Test
    fun `a run that ended early settles only a request still running`() {
        statuses.begun(RequestId(1))
        statuses.done(RequestId(1), "saved")
        statuses.settle(RequestId(1), "failed")
        assertEquals(RequestStatus.Done(RequestId(1), "saved"), statuses.status.value, "answered already")

        statuses.begun(RequestId(2))
        statuses.settle(RequestId(1), "failed")
        assertEquals(RequestStatus.InFlight(RequestId(2)), statuses.status.value, "an earlier request settles nothing")
        statuses.settle(RequestId(2), "failed")
        assertEquals(RequestStatus.Done(RequestId(2), "failed"), statuses.status.value)
    }
}
