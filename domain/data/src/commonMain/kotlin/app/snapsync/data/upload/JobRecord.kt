package app.snapsync.data.upload

import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.SealVerdict
import app.snapsync.domain.upload.CreatedJob
import app.snapsync.domain.upload.DestinationPath
import app.snapsync.domain.upload.MintsCreatedJob
import app.snapsync.domain.upload.MintsEndedJob
import app.snapsync.domain.upload.ReportedFailure
import app.snapsync.domain.upload.ReportedLanding
import app.snapsync.domain.upload.TransferEnd
import app.snapsync.domain.upload.UploadKey
import app.snapsync.ports.CreateOutcome

/**
 * **The platform's word, recorded** — the one place both transfer tiers translate what the platform answered into the
 * evidence a row's transition requires, and make that transition: a create it accepted becomes a requested row
 * ([CreatedJob], write-after-act), a job it reports ended becomes a settled or returned one ([ReportedLanding],
 * [ReportedFailure]).
 */
class JobRecord(private val transitions: RowTransitions) {

    /** The platform answered [outcome] to a create of [key] addressed to [destination]: recorded when accepted. */
    @OptIn(MintsCreatedJob::class)
    fun created(event: EventId, key: UploadKey, outcome: CreateOutcome, destination: DestinationPath): CreateAnswer =
        when (outcome) {
            CreateOutcome.CREATED -> CreateAnswer.Created(transitions.requested(event, CreatedJob(key), destination))
            CreateOutcome.LIMIT_EXCEEDED -> CreateAnswer.Full
            CreateOutcome.FAILED -> CreateAnswer.Failed("the platform made no job")
        }

    /** The platform reported [key]'s job [ended]: its row settled, or returned to needing a job. */
    @OptIn(MintsEndedJob::class)
    fun ended(event: EventId, key: UploadKey, ended: TransferEnd): RowTransition = when (ended) {
        TransferEnd.LANDED -> transitions.landed(event, ReportedLanding(key))
        TransferEnd.FAILED -> transitions.returned(event, ReportedFailure(key))
    }
}

/** What a tier's create of one row's upload came to. */
sealed interface CreateAnswer {
    /** The platform accepted the job, and its row was recorded requested — [recorded] is what the ledger made of it. */
    class Created(val recorded: RowTransition) : CreateAnswer

    /** The platform takes no more jobs now: the row still needs one, and work remains. */
    data object Full : CreateAnswer

    /** No job was made; the row still needs one. [detail] is diagnostics only. */
    data class Failed(val detail: String) : CreateAnswer

    /** The event's key cannot seal it now: nothing goes up unencrypted, and the row waits. */
    class Withheld(val because: SealVerdict.Withheld.Because) : CreateAnswer

    /** Nothing can be addressed to this device now — its id is not held here, or cannot be read. */
    data class Unaddressed(val detail: String) : CreateAnswer

    /** Where a failed attempt's bytes stand could not be learned: no transfer now, the next pass asks. */
    data object NotNow : CreateAnswer
}
