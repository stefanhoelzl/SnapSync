package app.snapsync.data.upload

import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.StagedFile
import app.snapsync.domain.upload.TransferEnd
import app.snapsync.domain.upload.UploadKey
import app.snapsync.format.UploadKeyText
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
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
     * The session says the transfer tagged [tag] ended — with [failure], or none for a landing: recorded durably, right
     * here, before returning, for the session tells it once and the process's runtime after its callback is not ours
     * to assume. Non-suspending for that reason. Its staged file goes now, unless it failed and will resume.
     */
    fun finished(event: EventId, tag: String, failure: TransferFailure?): FinishedAnswer {
        val ended = endOf(failure)
        kept(tag, StagedFile.after(ended))
        return when (val key = UploadKeyText.parse(tag)) {
            null -> FinishedAnswer.Unknown(tag)
            is UploadKey -> FinishedAnswer.Recorded(record.ended(event, key, ended))
        }
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

/** What recording a session transfer's end came to. */
sealed interface FinishedAnswer {
    /** The end was recorded on the transfer's row — [recorded] is what the ledger made of it. */
    class Recorded(val recorded: RowTransition) : FinishedAnswer

    /** The transfer's tag spells no upload key: no row is its. */
    data class Unknown(val tag: String) : FinishedAnswer
}
