package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.upload.CreateAnswer
import app.snapsync.data.upload.KeyLookup
import app.snapsync.data.upload.OwnPhotoResources
import app.snapsync.data.upload.SendFrom
import app.snapsync.data.upload.UploadTier
import app.snapsync.data.upload.WalkRecord
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.TopUpPlan
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadPass
import app.snapsync.domain.upload.UploadRow
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private val log = Log("CreateJobs")

/**
 * **Create the jobs a top-up planned**, one row at a time, until the platform takes no more — its refusal is the one
 * signal that work remains; the platform alone knows how many it takes, and on the app's tier that same limit bounds
 * the staged files on disk. Cancelled when the operating system's time is up, between two creations: each creation
 * and its record are one step the tier makes non-cancellable.
 *
 * A row is sent from the resource the walk just read when it is [inHand]; otherwise its photo is looked up again. A
 * photo that resolves to nothing has left the library — or, under a partial grant, the selection — and its row is
 * withdrawn by key; a library that cannot be read now is evidence of nothing, and the row is kept.
 */
class CreateJobs(
    private val tier: UploadTier,
    private val resources: OwnPhotoResources,
    private val walk: WalkRecord,
) {
    @EntryPointCall
    suspend operator fun invoke(event: EventId, plan: TopUpPlan, inHand: Map<UploadKey, SendFrom>): UploadPass {
        for (row in plan.rows) {
            currentCoroutineContext().ensureActive()
            if (created(event, row, inHand[row.key]) == CreateAnswer.Full) return UploadPass.WORK_LEFT
        }
        return UploadPass.CAUGHT_UP
    }

    private suspend fun created(event: EventId, row: UploadRow.Discovered, inHand: SendFrom?): CreateAnswer? =
        sent(event, row, inHand ?: found(event, row))

    private suspend fun sent(event: EventId, row: UploadRow.Discovered, from: SendFrom?): CreateAnswer? =
        from?.let { made(event, row, it) }

    private suspend fun made(event: EventId, row: UploadRow.Discovered, from: SendFrom): CreateAnswer {
        val answer = tier.create(event, row, from)
        logged(row, answer)
        return answer
    }

    private suspend fun found(event: EventId, row: UploadRow.Discovered): SendFrom? = when (
        val lookup = resources.find(
            row.key,
        )
    ) {
        is KeyLookup.Found -> lookup.from
        KeyLookup.Gone -> gone(event, row)
        KeyLookup.NotReadable -> null.also { log.i("the library cannot be read — keeping the row", "key" of row.key) }
    }

    private suspend fun gone(event: EventId, row: UploadRow.Discovered): SendFrom? {
        log.i("a planned row's photo is gone — withdrawing its row", "key" of row.key)
        walk.withdraw(event, listOf(row.key))
        return null
    }

    private suspend fun logged(row: UploadRow.Discovered, answer: CreateAnswer) = when (answer) {
        CreateAnswer.Full -> log.w("job limit reached — deferring the rest", "key" of row.key)
        else -> log.i("job create answered", "key" of row.key, "answer" of answer)
    }
}
