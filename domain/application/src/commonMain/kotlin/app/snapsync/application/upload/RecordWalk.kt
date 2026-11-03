package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.upload.WalkRecord
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.WalkDiff
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

private val log = Log("RecordWalk")

/**
 * **Record what a walk found** ([WalkDiff]): the departed rows withdrawn first — a photo that left is never listed by
 * the pass that saw it go — then the bare rows' detail filled and the new resources recorded as needing a job, each in
 * one transaction. It creates no job: the rows it recorded are the next top-up's work.
 */
class RecordWalk(private val walk: WalkRecord) {
    @EntryPointCall
    suspend operator fun invoke(event: EventId, diff: WalkDiff): RecordedWalk {
        logged("withdrawn", diff.departed.size, walk.withdraw(event, diff.departed))
        logged("filled", diff.bare.size, walk.backfill(event, diff.bare))
        logged("recorded", diff.fresh.size, walk.discover(event, diff.fresh))
        return RecordedWalk(added = diff.fresh.isNotEmpty())
    }

    private suspend fun logged(what: String, count: Int, written: StoreWrite) = when (written) {
        StoreWrite.Written -> log.i("walk rows $what", "rows" of count)
        is StoreWrite.Failed -> log.w("walk rows not $what", "rows" of count, "detail" of written.detail)
    }
}

/** What a walk recorded: whether it [added] rows — a top-up is due at once when it did. */
class RecordedWalk(val added: Boolean)
