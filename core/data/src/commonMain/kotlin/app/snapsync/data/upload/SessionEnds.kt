package app.snapsync.data.upload

import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.StagedFile
import app.snapsync.domain.upload.TransferEnd
import app.snapsync.domain.upload.UploadKey
import app.snapsync.format.UploadKeyText
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.TransferSession

/**
 * **How the app's own session transfers end** — each end the session reports recorded on its row the moment it is
 * reported, and every live transfer cancelled when a leave stops them. A transfer's tag is its row's key; whether its
 * staged file stays is [StagedFile]'s.
 */
class SessionEnds(
    private val session: TransferSession,
    private val staged: StagedFiles,
    private val record: JobRecord,
) {
    private val log = Log("SessionEnds")

    /**
     * The session says a transfer ended ([end]): recorded durably, right
     * here, before returning, for the session tells it once and the process's runtime after its callback is not ours
     * to assume. Non-suspending for that reason. Its staged file goes now, unless it failed and will resume. With no
     * event joined — a leave came first — no row is its, and nothing is recorded.
     */
    fun finished(event: EventId?, end: SessionEnd): FinishedAnswer {
        val ended = endOf(end.failure)
        kept(end.tag, StagedFile.after(ended))
        return when (event) {
            null -> FinishedAnswer.Unjoined
            is EventId -> recorded(event, end.tag, ended)
        }
    }

    private fun recorded(event: EventId, tag: String, ended: TransferEnd): FinishedAnswer =
        when (val key = UploadKeyText.parse(tag)) {
            null -> FinishedAnswer.Unknown(tag)
            is UploadKey -> FinishedAnswer.Recorded(record.ended(event, key, ended))
        }

    /**
     * Cancel every live transfer and delete its staged file — what a leave stops. Asks the session which transfers
     * exist rather than a registry of its own, so it also cancels those a relaunch inherited. Rows are untouched.
     */
    suspend fun cancelAll() {
        for (transfer in session.live()) {
            session.cancel(transfer)
            transfer.tag?.let(staged::release)
        }
        log.i("every upload transfer cancelled")
    }

    private fun endOf(failure: TransferFailure?): TransferEnd = when (failure) {
        null -> TransferEnd.LANDED
        is TransferFailure -> TransferEnd.FAILED.also {
            log.unlabelled.i("an upload transfer failed", "detail" of failure.detail)
        }
    }

    private fun kept(tag: String, file: StagedFile) = when (file) {
        StagedFile.KEPT -> Unit
        StagedFile.RELEASED -> staged.release(tag)
    }
}

/** The session's report that the transfer tagged [tag] ended — with [failure], or none for a landing. */
class SessionEnd(val tag: String, val failure: TransferFailure?)

/** What recording a session transfer's end came to. */
sealed interface FinishedAnswer {
    /** The end was recorded on the transfer's row — [recorded] is what the ledger made of it. */
    class Recorded(val recorded: RowTransition) : FinishedAnswer

    /** The transfer's tag spells no upload key: no row is its. */
    data class Unknown(val tag: String) : FinishedAnswer

    /** No event is joined — a leave came first: no row can be this transfer's. */
    data object Unjoined : FinishedAnswer
}
