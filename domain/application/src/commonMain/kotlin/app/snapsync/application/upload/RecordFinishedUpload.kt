package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.upload.FinishedAnswer
import app.snapsync.data.upload.SessionEnd
import app.snapsync.data.upload.SessionEnds
import app.snapsync.domain.event.EventId

/**
 * **Record that a session upload ended** — the platform tells it once, so it is recorded right here, before the
 * delivery returns: non-suspending, against the event joined now. One that ends after a leave belongs to no row.
 */
class RecordFinishedUpload(private val ends: SessionEnds, private val membership: MembershipRepository) {
    @EntryPointCall
    operator fun invoke(end: SessionEnd): FinishedAnswer = ends.finished(joined(), end)

    private fun joined(): EventId? = when (val read = membership.read()) {
        is MembershipRead.Joined -> read.membership.event.id
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> null
    }
}
