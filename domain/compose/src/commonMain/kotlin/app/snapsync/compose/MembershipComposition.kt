package app.snapsync.compose

import app.snapsync.application.encryption.RestoreEventKey
import app.snapsync.application.membership.ClaimStateCheck
import app.snapsync.application.membership.DeliverPendingLeaves
import app.snapsync.application.membership.EnrollDevice
import app.snapsync.application.membership.EnterMembership
import app.snapsync.application.membership.ForgetMembership
import app.snapsync.application.membership.LeaveEvent
import app.snapsync.application.membership.ReconfigureEvent
import app.snapsync.application.membership.RefreshMembership
import app.snapsync.application.membership.RenameEvent
import app.snapsync.entrypoints.MembershipBranch
import app.snapsync.query.membership.JoinDetailsLookup
import app.snapsync.query.membership.MembershipQuery
import app.snapsync.query.membership.SwitchLookup

/**
 * The membership's layers over [core] (`docs/patterns.md`): its use cases, its queries, and the branch every trigger
 * reads it through — each built once, on first touch.
 */
internal class MembershipLayers(private val core: AppCore) {
    private val membership get() = core.services.membership

    val enroll: EnrollDevice by lazy {
        EnrollDevice(membership, core.services.deviceIdentity, core.backend.join, core.services.eventKeyIds)
    }
    val enter: EnterMembership by lazy { EnterMembership(membership) }
    val leave: LeaveEvent by lazy { LeaveEvent(membership) }
    val refresh: RefreshMembership by lazy {
        RefreshMembership(membership, core.backend.directory, core.services.wallClock)
    }
    val reconfigure: ReconfigureEvent by lazy { ReconfigureEvent(membership) }
    val rename: RenameEvent by lazy { RenameEvent(membership, core.backend.rename) }
    val deliver: DeliverPendingLeaves by lazy { DeliverPendingLeaves(core.services.pendingLeaves, core.backend.leave) }
    val claimState: ClaimStateCheck by lazy { ClaimStateCheck(core.services.eventChecks, core.services.wallClock) }
    val forget: ForgetMembership by lazy { ForgetMembership(membership) }
    val restoreKey: RestoreEventKey by lazy {
        RestoreEventKey(membership, core.services.eventKeyStore, core.services.eventKeyIds)
    }

    val query: MembershipQuery by lazy { MembershipQuery(membership) }
    val joinDetails: JoinDetailsLookup by lazy { JoinDetailsLookup(core.backend.directory, core.services.eventKeyIds) }
    val switchLookup: SwitchLookup by lazy { SwitchLookup(membership, core.backend.directory, core.services.wallClock) }

    /** The membership as every trigger reads it. */
    val branch: MembershipBranch by lazy { MembershipBranch(query, refresh) }
}
