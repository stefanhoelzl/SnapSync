package app.snapsync.application.wake

import app.snapsync.application.EntryPointCall
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.time.WallClock
import app.snapsync.data.upload.OsUploader
import app.snapsync.data.wake.WakeArming
import app.snapsync.data.wake.WakeRequest
import app.snapsync.domain.event.EventPhase
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.wake.CadenceFacts
import app.snapsync.domain.wake.CadenceMembership
import app.snapsync.domain.wake.EventRunning
import app.snapsync.domain.wake.HeartbeatCadence
import app.snapsync.domain.wake.HeartbeatVerdict
import app.snapsync.domain.wake.LibraryWatch
import app.snapsync.domain.wake.StagedImports
import app.snapsync.domain.wake.UploadsLeft

/**
 * The re-arm a wake owes the next one, so photos upload without the app being opened (decision record
 * `changes/timely-background-receiving`, D1): made once the wake's own uploads have run — or were cut short — and
 * before the wake answers the operating system.
 *
 * After a wake whose uploads ran for a membership that contributes, the library-change wake is requested first, so a
 * device that is caught up still notices the next photo; where one stands (Android) the heartbeat need not look for
 * new photos itself, and where none can (iOS) the confirmed OS uploader stands in for it. Then the heartbeat is armed
 * at the cadence [CadenceFacts] answers — replacing the pending one — and stays pending while the device is joined.
 */
class RearmHeartbeat(
    private val arming: WakeArming,
    private val membership: MembershipRepository,
    private val clock: WallClock,
    private val grant: GrantRead,
    private val osUploader: OsUploader,
    private val mobileData: MobileDataStore,
) {
    @EntryPointCall
    suspend operator fun invoke(uploads: UploadsLeft, imports: StagedImports): RearmOutcome {
        val facts = CadenceFacts(
            membership = joined(membership.state.value),
            event = running(membership.state.value),
            access = grant.access(),
            osUploader = osUploader.standing(),
            uploads = uploads,
            imports = imports,
            libraryWatch = watched(uploads),
        )
        return when (val verdict = facts.cadence()) {
            HeartbeatVerdict.None -> RearmOutcome.NotJoined
            is HeartbeatVerdict.Arm -> armed(verdict.cadence)
        }
    }

    private suspend fun watched(uploads: UploadsLeft): LibraryWatch = when (uploads) {
        UploadsLeft.WORK_LEFT, UploadsLeft.CAUGHT_UP -> arming.watchLibrary()
        UploadsLeft.NOT_CONTRIBUTING -> LibraryWatch.NONE
    }

    private suspend fun armed(cadence: HeartbeatCadence): RearmOutcome =
        when (val answer = arming.arm(cadence.trigger(mobileData.photoNetwork()))) {
            WakeRequest.Standing -> RearmOutcome.Armed(cadence)
            WakeRequest.Unsupported, is WakeRequest.Refused -> RearmOutcome.NotArmed(answer)
        }

    private fun joined(read: MembershipRead): CadenceMembership = when (read) {
        is MembershipRead.Joined -> shares(read.membership)
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> CadenceMembership.NOT_JOINED
    }

    private fun shares(held: Membership): CadenceMembership = when (held.share.direction.includesUpload) {
        true -> CadenceMembership.SHARES
        false -> CadenceMembership.RECEIVES
    }

    private fun running(read: MembershipRead): EventRunning = when (read) {
        is MembershipRead.Joined -> phase(read.membership.event.range.phaseAt(clock.now()))
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> EventRunning.RUNNING
    }

    private fun phase(phase: EventPhase): EventRunning = when (phase) {
        EventPhase.Ended -> EventRunning.ENDED
        EventPhase.Upcoming, EventPhase.Running -> EventRunning.RUNNING
    }
}

/** What a re-arm came to. */
sealed interface RearmOutcome {
    /** No membership is held: no heartbeat is pending. */
    data object NotJoined : RearmOutcome

    data class Armed(val cadence: HeartbeatCadence) : RearmOutcome

    /** The operating system did not take it — logged where it answered. */
    data class NotArmed(val answer: WakeRequest) : RearmOutcome
}
