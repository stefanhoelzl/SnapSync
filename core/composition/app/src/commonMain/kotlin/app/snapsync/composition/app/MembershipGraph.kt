package app.snapsync.composition.app

import app.snapsync.application.encryption.RestoreEventKey
import app.snapsync.application.membership.ClaimStateCheck
import app.snapsync.application.membership.DeliverPendingLeaves
import app.snapsync.application.membership.EnrollDevice
import app.snapsync.application.membership.EnterMembership
import app.snapsync.application.membership.LeaveEvent
import app.snapsync.application.membership.ReconfigureEvent
import app.snapsync.application.membership.RefreshMembership
import app.snapsync.application.membership.RenameEvent
import app.snapsync.entrypoints.MembershipBranch
import app.snapsync.query.membership.JoinDetailsLookup
import app.snapsync.query.membership.MembershipQuery
import app.snapsync.query.membership.SwitchLookup
import kotlinx.coroutines.CoroutineDispatcher

/** The membership's layers: its use cases, its queries, and the branch every trigger reads it through. */
class MembershipGraph(services: ServicesGraph, backend: BackendGraph, lane: CoroutineDispatcher) {
    val enroll: EnrollDevice =
        EnrollDevice(services.membership, services.deviceIdentity, backend.join, services.eventKeyIds)
    val enter: EnterMembership = EnterMembership(services.membership)
    val leave: LeaveEvent = LeaveEvent(services.membership)
    val refresh: RefreshMembership = RefreshMembership(
        services.membership,
        backend.directory,
        services.eventKeyStore,
        services.wallClock,
    )
    val reconfigure: ReconfigureEvent = ReconfigureEvent(services.membership)
    val rename: RenameEvent = RenameEvent(services.membership, backend.rename, services.eventKeyStore)
    val deliver: DeliverPendingLeaves = DeliverPendingLeaves(services.pendingLeaves, backend.leave)
    val claimState: ClaimStateCheck = ClaimStateCheck(services.eventChecks, services.wallClock)
    val restoreKey: RestoreEventKey = RestoreEventKey(services.membership, services.eventKeyStore, services.eventKeyIds)
    val query: MembershipQuery = MembershipQuery(services.membership)
    val joinDetails: JoinDetailsLookup = JoinDetailsLookup(backend.directory, services.eventKeyIds, lane)
    val switchLookup: SwitchLookup = SwitchLookup(services.membership, backend.directory, services.wallClock)

    /** The membership as every trigger reads it. */
    val branch: MembershipBranch = MembershipBranch(query, refresh)
}
