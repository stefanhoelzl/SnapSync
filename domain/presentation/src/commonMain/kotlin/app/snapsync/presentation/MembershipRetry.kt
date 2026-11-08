package app.snapsync.presentation

import app.snapsync.domain.request.MembershipReadRequest
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.presentation.RenameRequests.answerTo
import kotlinx.coroutines.flow.update

/**
 * Trying again to read whether this device is in an event, from the screen that says it cannot be read: one request at
 * a time — a second tap while one runs does nothing — shown as working until it is answered.
 */
internal class MembershipRetry(
    private val cells: ScreenCells,
    private val commands: UserCommands,
    private val requests: RequestStatusFlows,
) {
    private val log = Log("MembershipRetry").unlabelled

    fun onRetry() = cells.runner.guarded(Guarded.MembershipRead) {
        val id = cells.ids.next()
        cells.local.update { it.copy(retryingRead = true) }
        try {
            log.i("membership read tried again", "request" of id)
            commands.retryMembershipRead.fire(MembershipReadRequest(id))
            requests.membershipRead.answerTo(id)
            log.i("membership read answered", "request" of id)
        } finally {
            cells.local.update { it.copy(retryingRead = false) }
        }
    }
}
