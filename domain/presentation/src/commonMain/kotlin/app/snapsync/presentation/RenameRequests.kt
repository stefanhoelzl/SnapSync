package app.snapsync.presentation

import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.query.RequestStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** A rename as the screen tells it: none of the member's, running, done — or refused, and why. */
internal sealed interface RenameStatus {
    data object Idle : RenameStatus

    data object InFlight : RenameStatus

    data object Succeeded : RenameStatus

    /** [fault] is how the service failed, on [RenameFailureReason.SERVER]; `null` when it answered. */
    data class Failed(val reason: RenameFailureReason, val fault: ServiceFault? = null) : RenameStatus
}

/**
 * Why a rename failed, so the dialog shows the right copy. There is deliberately no reason for a missing event: a lone
 * "not found" is one witness that the event is gone, and giving it copy would give it a meaning.
 */
internal enum class RenameFailureReason {
    /** The server refused the name: blank once trimmed, or too long. */
    INVALID_NAME,

    /** No answer, or any other. */
    SERVER,

    /** The service answered that the event's photos were deleted: the event is over. */
    EVENT_OVER,
}

/** The member's own rename [mine] as [status] answers it — another request's answer is none of the screen's. */
internal fun renameOf(status: RequestStatus<RenameResult>, mine: RequestId?): RenameStatus = when {
    mine == null -> RenameStatus.Idle
    status is RequestStatus.Done && status.id == mine -> status.value.shown()
    status is RequestStatus.InFlight && status.id == mine -> RenameStatus.InFlight
    else -> RenameStatus.Idle
}

private fun RenameResult.shown(): RenameStatus = when (this) {
    RenameResult.Renamed -> RenameStatus.Succeeded
    RenameResult.InvalidName -> RenameStatus.Failed(RenameFailureReason.INVALID_NAME)
    is RenameResult.Failed -> RenameStatus.Failed(RenameFailureReason.SERVER, fault)
    RenameResult.EventOver -> RenameStatus.Failed(RenameFailureReason.EVENT_OVER)
}

/** The answer to the member's own request [mine], once it arrives. */
internal suspend fun <T> StateFlow<RequestStatus<T>>.answerTo(mine: RequestId): T =
    (first { it is RequestStatus.Done && it.id == mine } as RequestStatus.Done).value
