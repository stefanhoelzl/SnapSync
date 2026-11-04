package app.snapsync.application.sharing

import app.snapsync.application.EntryPointCall
import app.snapsync.data.sharing.ManifestRecord
import app.snapsync.data.sharing.RecordRead
import app.snapsync.data.time.WallClock
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.sharing.PublishedDeclaration
import app.snapsync.domain.sharing.SettleNeed
import app.snapsync.domain.sharing.SharePhase
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

private val log = Log("CheckSettleNeed")

/**
 * **Whether the end of a wake owes [Membership.Active]'s share a settling look**: once the member's own range has
 * ended and no settled share was sent. Under a partial grant a wake itself walks nothing, so this is what makes sure
 * every device eventually says it has settled. A record it cannot read owes nothing now: the next wake asks again.
 */
class CheckSettleNeed(private val record: ManifestRecord, private val clock: WallClock) {
    @EntryPointCall
    suspend operator fun invoke(active: Membership.Active): SettleNeed {
        val phase = SharePhase.of(active.share.range, clock.now())
        return when (val held = record.read(active.event.id)) {
            is RecordRead.Held -> SettleNeed.of(phase, held.declaration)
            RecordRead.None -> SettleNeed.of(phase, PublishedDeclaration.NONE)
            is RecordRead.Unavailable -> SettleNeed.NOT_DUE.also {
                log.w("the manifest record is unreadable — settling waits for the next wake", "detail" of held.detail)
            }
        }
    }
}
