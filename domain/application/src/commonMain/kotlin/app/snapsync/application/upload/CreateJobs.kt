package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.upload.CreateAnswer
import app.snapsync.data.upload.KeyLookup
import app.snapsync.data.upload.OwnPhotoResources
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.RecentWalk
import app.snapsync.data.upload.RowRead
import app.snapsync.data.upload.SendFrom
import app.snapsync.data.upload.UploadTier
import app.snapsync.data.upload.WalkRecord
import app.snapsync.domain.asset.AssetId
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
 * What runs for a photo just before its job is created, once it is found and its row still needs one — the event
 * album's placement, which the entry point owns: placed first, a creation that fails or is cut leaves the photo placed,
 * and a photo gone from the library, or past the platform's refusal, is never placed.
 */
interface BeforeJob {
    suspend fun before(asset: AssetId)
}

/**
 * **Create the jobs a top-up planned**, one row at a time, until the platform takes no more — its refusal is the one
 * signal that work remains; the platform alone knows how many it takes, and on the app's tier that same limit bounds
 * the staged files on disk. Cancelled when the operating system's time is up, between two creations: each creation
 * and its record are one step the tier makes non-cancellable.
 *
 * A row is sent from the resource the walk just read when that walk handed it over ([RecentWalk]); otherwise its photo
 * is looked up again. A photo that resolves to nothing has left the library — or, under a partial grant, the
 * selection — and its row is withdrawn by key; a library that cannot be read now is evidence of nothing, and the row is
 * kept. Each row is read again just before its job: both uploaders write the one shared ledger, and the other may have
 * taken it since the plan was read — a second job would send the same bytes twice.
 */
class CreateJobs(
    private val rows: OwnUploadRows,
    private val tier: UploadTier,
    private val resources: OwnPhotoResources,
    private val walk: WalkRecord,
    private val recent: RecentWalk,
) {
    @EntryPointCall
    suspend operator fun invoke(event: EventId, plan: TopUpPlan, before: BeforeJob): UploadPass =
        Creation(event, before, recent.take()).all(plan.rows)

    /** One top-up's creations, for [event], sending from what the walk had [inHand]. */
    private inner class Creation(
        private val event: EventId,
        private val before: BeforeJob,
        private val inHand: Map<UploadKey, SendFrom>,
    ) {
        suspend fun all(planned: List<UploadRow.Discovered>): UploadPass {
            for (row in planned) {
                currentCoroutineContext().ensureActive()
                created(row).endedPass()?.let { return it }
            }
            return UploadPass.CAUGHT_UP
        }

        private suspend fun created(row: UploadRow.Discovered): CreateAnswer? =
            (inHand[row.key] ?: found(row))?.let { made(row, it) }

        private suspend fun made(row: UploadRow.Discovered, from: SendFrom): CreateAnswer? =
            when (val now = rows.row(event, row.key)) {
                is RowRead.Found -> stillNeeded(row, now.row, from)
                RowRead.Absent -> skipped(row, "the row was withdrawn since the plan")
                is RowRead.Unavailable -> skipped(row, "the row cannot be read now")
            }

        private suspend fun stillNeeded(row: UploadRow.Discovered, now: UploadRow, from: SendFrom) = when (now) {
            is UploadRow.Discovered -> creating(row, from)
            is UploadRow.Requested, is UploadRow.Completed -> skipped(row, "another uploader took it since the plan")
        }

        private suspend fun creating(row: UploadRow.Discovered, from: SendFrom): CreateAnswer {
            before.before(row.key.asset)
            val answer = tier.create(event, row, from)
            logged(row, answer)
            return answer
        }

        private suspend fun found(row: UploadRow.Discovered): SendFrom? = when (val lookup = resources.find(row.key)) {
            is KeyLookup.Found -> lookup.from
            KeyLookup.Gone -> gone(row)
            KeyLookup.NotReadable -> null.also { log.i("the library cannot be read — row kept", "key" of row.key) }
        }

        private suspend fun gone(row: UploadRow.Discovered): SendFrom? {
            log.i("a planned row's photo is gone — withdrawing its row", "key" of row.key)
            walk.withdraw(event, listOf(row.key))
            return null
        }
    }

    private suspend fun skipped(row: UploadRow.Discovered, why: String): CreateAnswer? =
        null.also { log.i("not creating", "why" of why, "key" of row.key) }

    private suspend fun logged(row: UploadRow.Discovered, answer: CreateAnswer) = when (answer) {
        CreateAnswer.Full -> log.w("job limit reached — deferring the rest", "key" of row.key)
        is CreateAnswer.Unverified -> log.w("no proof is held — nothing is sent until verified", "key" of row.key)
        else -> log.i("job create answered", "key" of row.key, "answer" of answer)
    }
}

/**
 * Whether [this] create ends the pass, and as what: the platform took no more — work remains — or this phone holds no
 * proof, so every later row would wait for the same reason (change `withhold-unverified-uploads`).
 */
internal fun CreateAnswer?.endedPass(): UploadPass? = when (this) {
    CreateAnswer.Full -> UploadPass.WORK_LEFT
    is CreateAnswer.Unverified -> UploadPass.UNVERIFIED
    else -> null
}
