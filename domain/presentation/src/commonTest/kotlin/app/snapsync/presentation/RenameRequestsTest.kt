package app.snapsync.presentation

import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.request.RequestId
import app.snapsync.query.RequestStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rename dialog reads only the answer to its own request — another request's is none of its business. */
class RenameRequestsTest {

    private val mine = RequestId(2)
    private val other = RequestId(1)

    @Test
    fun `the member's own request reads as it stands`() {
        assertEquals(RenameStatus.InFlight, renameOf(RequestStatus.InFlight(mine), mine))
        assertEquals(RenameStatus.Succeeded, renameOf(RequestStatus.Done(mine, RenameResult.Renamed), mine))
        assertEquals(
            RenameStatus.Failed(RenameFailureReason.INVALID_NAME),
            renameOf(RequestStatus.Done(mine, RenameResult.InvalidName), mine),
        )
        assertEquals(
            RenameStatus.Failed(RenameFailureReason.SERVER),
            renameOf(RequestStatus.Done(mine, RenameResult.Failed()), mine),
        )
    }

    @Test
    fun `no request of the member's is idle`() {
        assertEquals(RenameStatus.Idle, renameOf(RequestStatus.Done(other, RenameResult.Renamed), null))
    }

    @Test
    fun `the member's request unanswered is in flight, whether or not its run has begun - another's answer is not its`() {
        assertEquals(RenameStatus.InFlight, renameOf(RequestStatus.Idle, mine))
        assertEquals(RenameStatus.InFlight, renameOf(RequestStatus.InFlight(other), mine))
        assertEquals(RenameStatus.InFlight, renameOf(RequestStatus.Done(other, RenameResult.Renamed), mine))
    }
}
