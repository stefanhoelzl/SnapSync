package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crypto.DownloadOpening
import app.snapsync.data.crypto.Landing
import app.snapsync.data.crypto.OpenAnswer
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.ImportQueue
import app.snapsync.data.receiving.Landed
import app.snapsync.data.receiving.StageRecord
import app.snapsync.data.receiving.StagingArea
import app.snapsync.data.receiving.TransferTag
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.domain.receiving.StagingFolder
import app.snapsync.domain.receiving.TransferFacts
import app.snapsync.domain.receiving.TransferVerdict
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

/**
 * Keep a finished transfer's bytes — INLINE, on the platform's delivering thread, which deletes the temporary file
 * when it returns.
 *
 * A finished transfer is not a good transfer: a background session delivers an HTTP error as a *successful* transfer
 * of the error body, so it is judged first ([TransferVerdict]) — staging makes these bytes the store's truth, and a
 * staged error body would be imported, fail, and never be downloaded again. Then it is moved to the staging path its
 * tag names — a relaunched process never started the transfer and remembers nothing of it — and an encrypted event's
 * bytes land beside it, sealed, to be opened by [RecordStaged]. Nothing here suspends.
 */
class StageDownload(private val staging: StagingArea, private val opening: DownloadOpening) {
    private val log = Log("StageDownload")

    @EntryPointCall
    operator fun invoke(tag: String, facts: TransferFacts, temp: String): StageOutcome = logged(
        when (TransferVerdict.of(facts)) {
            TransferVerdict.Stage -> tagged(TransferTag.parse(tag), temp)
            // Never recorded as staged: the next read downloads it again.
            TransferVerdict.Reject -> StageOutcome.Rejected(facts)
        },
    )

    private fun tagged(tag: TransferTag?, temp: String): StageOutcome = when (tag) {
        null -> StageOutcome.UnknownTag
        else -> landed(tag, opening.landing(tag.stagedAt.value), temp)
    }

    private fun landed(tag: TransferTag, landing: Landing, temp: String): StageOutcome =
        when (val landed = staging.land(temp, StagedPath(landing.stagedAt))) {
            Landed.Kept -> StageOutcome.Landed(tag, landing)
            is Landed.NotKept -> StageOutcome.NotKept(landed.detail)
        }

    private fun logged(outcome: StageOutcome): StageOutcome {
        when (outcome) {
            is StageOutcome.Landed -> Unit
            is StageOutcome.Rejected -> log.unlabelled.w(
                "a finished transfer is rejected",
                "status" of outcome.facts.status,
                "expected" of outcome.facts.expectedBytes,
                "received" of outcome.facts.receivedBytes,
            )
            StageOutcome.UnknownTag -> log.unlabelled.w("a finished transfer carries a tag no build wrote")
            is StageOutcome.NotKept -> log.unlabelled.w(
                "a finished transfer's bytes were not kept; it is downloaded again",
                "why" of outcome.detail,
            )
        }
        return outcome
    }
}

/** What a finished transfer came to on its delivering thread. */
sealed interface StageOutcome {
    /** The bytes are kept at [landing]: [RecordStaged] records them — opening them first when sealed. */
    class Landed(val tag: TransferTag, val landing: Landing) : StageOutcome

    /** Nothing is kept: no record follows, and a later read downloads the resource again. */
    sealed interface Dropped : StageOutcome

    /** The body is an error, or shorter than promised: it is downloaded again. */
    class Rejected(val facts: TransferFacts) : Dropped

    data object UnknownTag : Dropped

    /** The bytes could not be moved into staging: they are downloaded again. */
    class NotKept(val detail: String) : Dropped
}

/**
 * Record a landed download as staged — opening an encrypted event's bytes into their path first, so only a file whose
 * every byte authenticated is staged; a file that does not open stays unstaged, and the next read downloads it again.
 * A staging no row takes has its file discarded by the store. Runs after the delivery returned, and the session's drain
 * report waits for it.
 */
class RecordStaged(private val queue: ImportQueue, private val opening: DownloadOpening) {
    private val log = Log("RecordStaged")

    @EntryPointCall
    suspend operator fun invoke(landed: StageOutcome.Landed): RecordOutcome = logged(
        landed.tag,
        when (opened(landed)) {
            OpenAnswer.Opened -> recorded(landed.tag)
            OpenAnswer.NotOpened -> RecordOutcome.NotOpened
        },
    )

    private suspend fun opened(landed: StageOutcome.Landed): OpenAnswer = opening.open(
        landed.landing,
        landed.tag.ref.sourceDeviceId,
        landed.tag.ref.sourceAssetId,
        landed.tag.role,
        landed.tag.fetchedFor(),
    )

    private suspend fun recorded(tag: TransferTag): RecordOutcome =
        when (val record = queue.staged(tag.ref, tag.resourceKey, tag.stagedAt)) {
            is DownloadRead.Ok -> outcome(record.value)
            is DownloadRead.Unavailable -> RecordOutcome.Unavailable(record.detail)
        }

    private fun outcome(record: StageRecord): RecordOutcome = when (record) {
        StageRecord.Recorded -> RecordOutcome.Recorded
        StageRecord.NoRow -> RecordOutcome.Orphaned
    }

    private suspend fun logged(tag: TransferTag, outcome: RecordOutcome): RecordOutcome {
        when (outcome) {
            RecordOutcome.Recorded, RecordOutcome.Orphaned, RecordOutcome.NotOpened ->
                log.i("staged", "resource" of tag.resourceKey, "recorded" of outcome)
            is RecordOutcome.Unavailable ->
                log.w("a staging could not be recorded; it is downloaded again", "why" of outcome.detail)
        }
        return outcome
    }
}

/** What a staged download's record came to. */
sealed interface RecordOutcome {
    /** Recorded: the wake's import sequence may import it. */
    data object Recorded : RecordOutcome

    /** Nothing was recorded: no import follows for it. */
    sealed interface NotRecorded : RecordOutcome

    /** No row took it — a leave's prune outran the transfer — and its file was discarded. */
    data object Orphaned : NotRecorded

    /** An encrypted download did not open: it is downloaded again. */
    data object NotOpened : NotRecorded

    class Unavailable(val detail: String) : NotRecorded
}

/**
 * A transfer ended — finished, failed or cancelled: its slot is free, and the next queued transfer starts. A failure is
 * retried by a later read, which plans the resource again.
 */
class FreeTransfer(private val queue: ImportQueue) {
    private val log = Log("FreeTransfer")

    @EntryPointCall
    suspend operator fun invoke(tag: String, error: String?) {
        error?.let { log.w("a download failed; it is retried", "why" of it) }
        queue.transferEnded(tag)
    }
}

/** The event a transfer's bytes were fetched for, if its tag names one — whose key opens them. */
private fun TransferTag.fetchedFor(): EventId? = when (val named = folder) {
    is StagingFolder.Event -> EventId.parse(named.name)
    StagingFolder.Legacy -> null
}
