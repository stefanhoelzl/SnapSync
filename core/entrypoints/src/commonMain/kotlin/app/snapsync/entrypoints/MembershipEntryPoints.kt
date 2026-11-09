@file:OptIn(EntryPointCall::class)

package app.snapsync.entrypoints

import app.snapsync.application.EntryPointCall
import app.snapsync.application.album.EnsureEventAlbum
import app.snapsync.application.membership.Admitted
import app.snapsync.application.membership.ClaimStateCheck
import app.snapsync.application.membership.DeliverPendingLeaves
import app.snapsync.application.membership.EnrollDevice
import app.snapsync.application.membership.EnrollOutcome
import app.snapsync.application.membership.EnterMembership
import app.snapsync.application.membership.EnterOutcome
import app.snapsync.application.membership.LeaveEvent
import app.snapsync.application.membership.LeaveOutcome
import app.snapsync.application.membership.LoadShareSet
import app.snapsync.application.membership.ReconfigureEvent
import app.snapsync.application.membership.ReconfigureOutcome
import app.snapsync.application.membership.RefreshMembership
import app.snapsync.application.membership.RenameEvent
import app.snapsync.application.push.RegisterPush
import app.snapsync.application.receiving.ForgetOtherEvents
import app.snapsync.application.receiving.PlanReceiving
import app.snapsync.application.receiving.ReceivingOf
import app.snapsync.application.receiving.RecognizeReceivedPhotos
import app.snapsync.application.receiving.ReportReceivedPosition
import app.snapsync.application.receiving.StopReason
import app.snapsync.application.receiving.StopReceiving
import app.snapsync.application.sharing.CountOwnPhotos
import app.snapsync.application.upload.ReconcileUploader
import app.snapsync.application.upload.StopUploads
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.KeepOutcome
import app.snapsync.data.gallery.GrantRead
import app.snapsync.domain.album.AlbumAsk
import app.snapsync.domain.membership.CheckCadence
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.Enrolled
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.LeftMembership
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.domain.push.PushTrigger
import app.snapsync.domain.receiving.ReceivingVerdict
import app.snapsync.domain.receiving.UnionPage
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.domain.request.JoinRequest
import app.snapsync.domain.request.RenameRequest
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.request.SettingsRequest
import app.snapsync.domain.request.SwitchRequest
import app.snapsync.domain.upload.UploaderChange
import app.snapsync.entrypoints.MembershipEntryPoints.membership
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.query.membership.MembershipQuery
import app.snapsync.query.membership.MembershipStatuses
import app.snapsync.query.membership.SwitchLookup
import app.snapsync.scheduling.BackgroundEntryPoint
import app.snapsync.scheduling.Exclusion
import app.snapsync.scheduling.Run
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.Steps

// ── The membership: what a member's requests do, and how every trigger reads the membership ─────────────────────────
//
// A member's request — a join confirmed, a leave, settings changed, a rename, another event's invite — is answered on
// its own status (`MembershipStatuses`), keyed by the request: the tap returns at once with its id, and the work runs
// here, detached. Every request run ends answered, however it ended (`ensure("answer")`).

/** The member confirmed a join: enrolled, then entered — and what follows a join. */
class OnJoinConfirmed(
    runtime: Runtime,
    private val enroll: EnrollDevice,
    private val enrolling: Enrolling,
    private val statuses: MembershipStatuses,
) : BackgroundEntryPoint<JoinRequest>(runtime) {
    override suspend fun run(arg: JoinRequest, on: Run) = on.steps {
        statuses.join.begun(arg.id)
        ensure("answer") { statuses.join.settle(arg.id, JoinCommit.Failed()) }
        when (val admitted = required { enroll.admit(arg.choice) }) {
            is Admitted -> required { enrolling.run(arg.id, admitted, on) }
            is EnrollOutcome.NotEnrolled -> required { statuses.join.done(arg.id, admitted.answer) }
        }
    }
}

/**
 * An admitted join: its key kept, then the enrolment, then the join itself. The key before the enrolment, and both
 * before the membership: a key the store refuses fails the join before the event counts this device — never
 * half-joined. A key kept with no membership is inert, and the next join, leave or reset replaces it.
 */
class Enrolling(
    private val keys: EventKeyStore,
    private val enroll: EnrollDevice,
    private val joining: Joining,
    private val statuses: MembershipStatuses,
) {
    suspend fun run(id: RequestId, admitted: Admitted, on: Run) = on.steps {
        when (val kept = required { keys.keep(admitted.key, admitted.membership.event.id) }) {
            is KeepOutcome.Kept -> when (val enrolled = required { enroll(admitted, kept.evidence) }) {
                is EnrollOutcome.Enrolled -> required { joining.run(id, enrolled.evidence, on) }
                is EnrollOutcome.NotEnrolled -> required { statuses.join.done(id, enrolled.answer) }
            }
            is KeepOutcome.Refused -> required { statuses.join.done(id, JoinCommit.Failed()) }
        }
    }
}

/** The member left — from the leave question, a switch's confirmation, or a quiet switch. */
class OnLeaveConfirmed(
    runtime: Runtime,
    private val leave: LeaveEvent,
    private val leaving: Leaving,
) : BackgroundEntryPoint<Unit>(runtime) {
    override suspend fun run(arg: Unit, on: Run) = on.steps {
        when (val left = required { leave() }) {
            is LeaveOutcome.Left -> required { leaving.run(left.evidence, on) }
            // Nothing ran: the member is fully joined, or never was.
            is LeaveOutcome.NotLeft -> Unit
        }
    }
}

/** Another event's invite loaded while joined: whether leaving the joined one may happen without asking. */
class OnInviteForOtherEvent(
    runtime: Runtime,
    private val lookup: SwitchLookup,
    private val held: HeldCheck,
    private val statuses: MembershipStatuses,
) : BackgroundEntryPoint<SwitchRequest>(runtime) {
    override suspend fun run(arg: SwitchRequest, on: Run) = on.steps {
        statuses.switch.begun(arg.id)
        ensure("answer") { statuses.switch.settle(arg.id, SwitchVerdict.Ask) }
        val facts = required { lookup.facts() }
        val holds = when (facts.needsReceivedCheck) {
            true -> required { held.run(on) }
            false -> null
        }
        required { statuses.switch.done(arg.id, SwitchVerdict.of(facts, holds, arg.invite)) }
    }
}

/** The member changed the joined event's settings. */
class OnSettingsChanged(
    runtime: Runtime,
    private val membership: MembershipBranch,
    private val reconfigure: ReconfigureEvent,
    private val settled: AfterSettings,
    private val statuses: MembershipStatuses,
) : BackgroundEntryPoint<SettingsRequest>(runtime) {
    override suspend fun run(arg: SettingsRequest, on: Run) = on.steps {
        statuses.settings.begun(arg.id)
        ensure("answer") { statuses.settings.settle(arg.id, SettingsResult.NotCurrent) }
        membership(membership, Freshness.Cached) {
            active { joined ->
                when (val saved = required { reconfigure(joined, arg.choice) }) {
                    is ReconfigureOutcome.Saved -> required { settled.run(arg.id, joined, saved.active, on) }
                    is ReconfigureOutcome.NotSaved -> required { statuses.settings.done(arg.id, saved.answer) }
                }
            }
        }
    }
}

/** The member renamed the joined event. */
class OnRenameRequested(
    runtime: Runtime,
    private val membership: MembershipBranch,
    private val rename: RenameEvent,
    private val statuses: MembershipStatuses,
) : BackgroundEntryPoint<RenameRequest>(runtime) {
    override suspend fun run(arg: RenameRequest, on: Run) = on.steps {
        statuses.rename.begun(arg.id)
        ensure("answer") { statuses.rename.settle(arg.id, RenameResult.Failed()) }
        membership(membership, Freshness.Cached) {
            active { joined ->
                val renamed = required { rename(joined, arg.event, arg.name) }
                required { statuses.rename.done(arg.id, renamed.answer) }
            }
        }
    }
}

/**
 * A join the server admitted: what it keeps of the membership BEFORE the membership is written — its key, its share
 * set, the photos an earlier install received — then the membership itself, and what follows. The join holds the
 * membership [change] from the key on, so no sweep of a failed leave's leftovers takes what is the join's.
 */
class Joining(
    private val change: Exclusion,
    private val shareSet: LoadShareSet,
    private val forgetOtherEvents: ForgetOtherEvents,
    private val recognize: RecognizeReceivedPhotos,
    private val enter: EnterMembership,
    private val joined: AfterJoin,
    private val statuses: MembershipStatuses,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(id: RequestId, enrolled: Enrolled, on: Run) = on.steps {
        ensure("membership change") { change.release() }
        required { change.hold() }
        // The share set before the membership: no cycle ever sees the new membership over the previous one's ledger.
        required { shareSet(enrolled.membership) }
        // The photos an earlier install received are recognised before any uploader sees the membership — against
        // every photo of the event, after forgetting which photos were any other event's.
        isolated("forget other events") { forgetOtherEvents(enrolled.membership) }
        val recognised = required { recognize(enrolled.membership, UnionTrigger.JOIN) }
        when (val entered = required { enter(enrolled) }) {
            // The union the recognition read, when it did: one union read per join.
            is EnterOutcome.Entered -> required { joined.run(id, entered.active, recognised.read, on) }
            // Joined already, or nothing written — the server's enrolment stands: a retry enrols again.
            is EnterOutcome.NotEntered -> required { statuses.join.done(id, entered.answer) }
        }
    }
}

/** What follows entering a membership: the uploads, the counts, the album, the others' photos and the push. */
class AfterJoin(
    private val reconcile: ReconcileUploader,
    private val arm: UploaderArm,
    private val count: CountOwnPhotos,
    private val album: EventAlbum,
    private val receivingOf: ReceivingOf,
    private val plan: PlanReceiving,
    private val registerPush: RegisterPush,
    private val statuses: MembershipStatuses,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(id: RequestId, joined: Membership.Active, known: UnionPage?, on: Run) = on.steps {
        val armed = required { reconcile(UploaderChange.JOIN) }
        isolated("arm") { arm.apply(armed) }
        isolated("total") { count() }
        required { album.run(joined, AlbumAsk.OptIn, on) }
        // The join itself starts receiving the event's history — no opening, no wake.
        when (val receiving = required { receivingOf(joined) }) {
            is ReceivingVerdict.Receives -> isolated(
                "receive",
            ) { plan(receiving.receiving, UnionTrigger.JOIN, known = known) }
            is ReceivingVerdict.NotReceiving -> Unit
        }
        isolated("push") { registerPush(PushTrigger.JOINED) }
        required { statuses.join.done(id, JoinCommit.Committed) }
    }
}

/** What a committed leave stops — every step its own, so one that fails leaves the rest done. */
class Leaving(
    private val stop: StopUploads,
    private val receiving: StopReceiving,
    private val keys: EventKeyStore,
    private val deliver: DeliverPendingLeaves,
) {
    private val log = Log("Leaving")

    @OptIn(EntryPointCall::class)
    suspend fun run(left: LeftMembership, on: Run) = on.steps {
        required { log.i("left", "event" of left.event) }
        isolated("stop uploads") { stop(left) }
        isolated("stop receiving") { receiving(StopReason.Left(left)) }
        isolated("forget key") { keys.forget(left) }
        detached { deliver() }
    }
}

/** What a saved settings change re-drives, before the member is told it is saved. */
class AfterSettings(
    private val count: CountOwnPhotos,
    private val reconcile: ReconcileUploader,
    private val arm: UploaderArm,
    private val album: EventAlbum,
    private val receivingOf: ReceivingOf,
    private val plan: PlanReceiving,
    private val stop: StopReceiving,
    private val report: ReportReceivedPosition,
    private val statuses: MembershipStatuses,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(id: RequestId, before: Membership.Active, saved: Membership.Active, on: Run) = on.steps {
        isolated("total") { count() }
        // A kick whatever the direction: the cycle's policy decides what uploads; nothing in flight is cancelled.
        val armed = required { reconcile(UploaderChange.RECONFIGURE) }
        isolated("arm") { arm.apply(armed) }
        required { album.run(saved, AlbumAsk.ofSave(before, saved), on) }
        when (val receiving = required { receivingOf(saved) }) {
            // Receiving: the event hears it at once, so what a share-only phase told it never lets it finish early.
            is ReceivingVerdict.Receives -> {
                detached { plan(receiving.receiving, UnionTrigger.RECONFIGURE) }
                detached { report.receivesAgain(saved) }
            }
            ReceivingVerdict.SharesOnly -> isolated("stop receiving") { stop(StopReason.SharesOnly(saved)) }
            ReceivingVerdict.KeyLost -> Unit
        }
        required { statuses.settings.done(id, SettingsResult.Saved) }
    }
}

/**
 * The event album a membership saves into, after a join or a settings change: made when the member wants one and the
 * grant allows it, and the photos already held gathered into it — each its own step, so one that fails leaves the other
 * done. [ask] says whether it was the member's opt-in, the only ask that
 * brings back an album they deleted.
 */
class EventAlbum(
    private val ensure: EnsureEventAlbum,
    private val gathering: AlbumGathering,
    private val grant: GrantRead,
) {
    suspend fun run(membership: Membership.Active, ask: AlbumAsk, on: Run) = on.steps {
        isolated("album") { ensure(membership, grant.access(), ask) }
        isolated("album gather") { gathering.requested() }
    }
}

/**
 * What a leave that failed half-way may have left — a key, downloads, an upload registration — swept while no
 * membership is held, and never while a join holds the membership [change]: what it would sweep is the join's own —
 * then nothing, and the next opening sweeps again.
 */
class SweepLeftovers(
    private val membership: MembershipBranch,
    private val keys: EventKeyStore,
    private val receiving: StopReceiving,
    private val stop: StopUploads,
    private val change: Exclusion,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(on: Run) = on.steps {
        membership(membership, Freshness.Cached) {
            notJoined { none ->
                isolated("leftover key") { change.unlessHeld("leftover key") { keys.forget(none) } }
                isolated("leftover downloads") {
                    change.unlessHeld("leftover downloads") { receiving(StopReason.NotJoined(none)) }
                }
                isolated("leftover registration") { change.unlessHeld("leftover registration") { stop(none) } }
            }
        }
    }
}

// ── The membership, read the same way by every trigger ──────────────────────────────────────────────────────────────

/** How fresh the membership a trigger acts on must be. */
enum class Freshness {
    /** As this process holds it — re-read at every entry. */
    Cached,

    /** And, for an active one, brought up to the server's word first (`RefreshMembership`). */
    Refreshed,
}

/** The membership as a trigger reads it, and the refresh a fresh read asks for. */
class MembershipBranch(internal val query: MembershipQuery, internal val refresh: RefreshMembership)

/** The branches of [membership]: each takes the steps of what the membership is in that state. */
class MembershipBranches internal constructor(private val steps: Steps) {
    internal var active: Branch<Membership.Active> = Omitted("active")
    internal var deleted: Branch<Membership.Deleted> = Omitted("deleted")
    internal var notJoined: Branch<ConfirmedNotJoined> = Omitted("not joined")

    fun active(steps: suspend Steps.(Membership.Active) -> Unit) {
        active = given(steps)
    }

    fun deleted(steps: suspend Steps.(Membership.Deleted) -> Unit) {
        deleted = given(steps)
    }

    fun notJoined(steps: suspend Steps.(ConfirmedNotJoined) -> Unit) {
        notJoined = given(steps)
    }

    /** The branch [body] writes, run as steps of the entry point's run. */
    private fun <T> given(body: suspend Steps.(T) -> Unit): Branch<T> = object : Branch<T> {
        override suspend fun run(value: T) = steps.body(value)
    }
}

/** One branch of [membership]. */
interface Branch<in T> {
    suspend fun run(value: T)
}

private class Omitted<T>(private val state: String) : Branch<T> {
    override suspend fun run(value: T) = log.i("membership: nothing to do", "state" of state)
}

private val log = Log("membership")

// ── The membership at a wake's start and end ────────────────────────────────────────────────────────────────────────

/**
 * The active membership brought up to the server's word — its name, members and open-until date follow it — and, when
 * the server confirms its photos deleted, every transfer stopped; the membership stays, shown as over. Any wake may,
 * since marking it deleted destroys nothing.
 */
class StateRefresh(private val membership: MembershipBranch, private val transfers: DeletedTransfers) {
    suspend fun run(on: Run) = on.steps {
        membership(membership, Freshness.Refreshed) {
            deleted { deleted -> isolated("stop transfers") { transfers.stop(deleted, on) } }
        }
    }
}

/** What a deleted event's membership stops: every upload and download, as a leave stops them. */
class DeletedTransfers(private val stopUploads: StopUploads, private val stop: StopReceiving) {
    @OptIn(EntryPointCall::class)
    suspend fun stop(deleted: Membership.Deleted, on: Run) = on.steps {
        isolated("stop receiving") { stop(StopReason.Deleted(deleted)) }
        isolated("stop uploads") { stopUploads(deleted) }
    }
}

/**
 * The end of every wake whose tail covered the whole pass: the others' photos read at most once an hour (when [kind]
 * reads them), the leaves the backend has not confirmed, this device's share settled once its own range has ended, the
 * event's state read once its range has ended — at most once an hour for a bounded wake — and how far this device
 * holds the others' photos reported.
 */
class EndOfWake(
    private val membership: MembershipBranch,
    private val photoCheck: PhotoCheck,
    private val deliver: DeliverPendingLeaves,
    private val settle: SettleShare,
    private val claim: ClaimStateCheck,
    private val stateRefresh: StateRefresh,
    private val received: ReceivedPositionReport,
) {
    suspend fun run(kind: WakeKind, on: Run) = on.steps {
        when (kind.checksPhotos) {
            true -> required { photoCheck.run(on) }
            false -> Unit
        }
        isolated("pending leaves") { deliver() }
        required { settle.run(on) }
        membership(membership, Freshness.Cached) {
            active { joined ->
                when (required { claim(joined, kind.boundsEventRead) }) {
                    CheckCadence.Due -> required { stateRefresh.run(on) }
                    CheckCadence.NotYet -> Unit
                }
            }
        }
        isolated("received report") { received.run(on) }
    }
}

object MembershipEntryPoints {
    /**
     * Run the branch [build] names for the membership as [freshness] reads it — `active`, `deleted` or `notJoined` —
     * [Freshness.Refreshed] bringing an active one up to the server's word first, so the branch runs over what it holds
     * after. A state no branch names, and a membership that cannot be read, is logged and does nothing.
     */
    suspend fun Steps.membership(of: MembershipBranch, freshness: Freshness, build: MembershipBranches.() -> Unit) {
        val branches = MembershipBranches(this).apply(build)
        when (freshness) {
            Freshness.Cached -> Unit
            Freshness.Refreshed -> refreshed(of)
        }
        read(of.query.state.value, branches)
    }

    /** The active membership held, if any, brought up to the server's word. */
    private suspend fun Steps.refreshed(of: MembershipBranch) =
        of.query.active()?.let { active -> required { of.refresh(active) } }

    private suspend fun read(read: MembershipRead, branches: MembershipBranches) = when (read) {
        is MembershipRead.Joined -> held(read.membership, branches)
        is MembershipRead.NotJoined -> branches.notJoined.run(read.evidence)
        is MembershipRead.Unavailable -> log.i("membership: unreadable, nothing to do")
    }

    private suspend fun held(held: Membership, branches: MembershipBranches) = when (held) {
        is Membership.Active -> branches.active.run(held)
        is Membership.Deleted -> branches.deleted.run(held)
    }
}
