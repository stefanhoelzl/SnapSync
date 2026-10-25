@file:OptIn(EntryPointCall::class)

package app.snapsync.entrypoints

import app.snapsync.application.EntryPointCall
import app.snapsync.application.membership.Admitted
import app.snapsync.application.membership.ClaimStateCheck
import app.snapsync.application.membership.DeliverPendingLeaves
import app.snapsync.application.membership.EnrollDevice
import app.snapsync.application.membership.EnrollOutcome
import app.snapsync.application.membership.EnterMembership
import app.snapsync.application.membership.EnterOutcome
import app.snapsync.application.membership.LeaveEvent
import app.snapsync.application.membership.LeaveOutcome
import app.snapsync.application.membership.ReconfigureEvent
import app.snapsync.application.membership.ReconfigureOutcome
import app.snapsync.application.membership.RefreshMembership
import app.snapsync.application.membership.RenameEvent
import app.snapsync.data.membership.MembershipRead
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.CheckCadence
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.Enrolled
import app.snapsync.domain.membership.InviteFact
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.LeftMembership
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.domain.request.RequestId
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.album.AlbumGather
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.ReceivedReport
import app.snapsync.feature.download.stopLeftovers
import app.snapsync.feature.membership.JoinUnion
import app.snapsync.feature.membership.ReceivedPhotoAdoption
import app.snapsync.feature.membership.ShareSetLoad
import app.snapsync.feature.push.PushRegistration
import app.snapsync.feature.status.StatusRefresh
import app.snapsync.feature.upload.ShareSettlement
import app.snapsync.feature.upload.UploadTransitions
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.model.UnionTrigger
import app.snapsync.query.membership.MembershipQuery
import app.snapsync.query.membership.MembershipStatuses
import app.snapsync.query.membership.SwitchLookup
import app.snapsync.scheduling.BackgroundEntryPoint
import app.snapsync.scheduling.Run
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.Steps
import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.gallery.GalleryAccessState
import app.snapsync.services.membership.MembershipProjection
import app.snapsync.services.membership.MembershipTransition
import app.snapsync.services.membership.asConfig
import app.snapsync.services.push.PushTokenSource

// ── The membership: what a member's requests do, and how every trigger reads the membership ─────────────────────────
//
// A member's request — a join confirmed, a leave, settings changed, a rename, another event's invite — is answered on
// its own status (`MembershipStatuses`), keyed by the request: the tap returns at once with its id, and the work runs
// here, detached. Every request run ends answered, however it ended (`ensure("answer")`).

/** A join the member confirmed: [id] is answered on the join status. */
class JoinRequest(val id: RequestId, val choice: JoinChoice)

/** Settings the member changed: [id] is answered on the settings status. */
class SettingsRequest(val id: RequestId, val choice: ShareChoice)

/** A rename of [event] the member asked for: [id] is answered on the rename status. */
class RenameRequest(val id: RequestId, val event: EventId, val name: String)

/** Another event's invite, loaded: [id] is answered with the switch verdict. */
class SwitchRequest(val id: RequestId, val invite: InviteFact)

/** The member confirmed a join: enrolled, then entered — and what follows a join. */
class OnJoinConfirmed(
    runtime: Runtime,
    private val enroll: EnrollDevice,
    private val keys: EventKeys,
    private val joining: Joining,
    private val statuses: MembershipStatuses,
) : BackgroundEntryPoint<JoinRequest>(runtime) {
    override suspend fun run(arg: JoinRequest, on: Run) = on.steps {
        ensure("answer") { statuses.join.settle(arg.id, JoinCommit.Failed()) }
        when (val admitted = required { enroll.admit(arg.choice) }) {
            is Admitted -> {
                // The key before the enrolment, and both before the membership: a key the store refuses fails the join
                // before the event counts this device — never half-joined. A key kept with no membership is inert, and
                // the next join, leave or reset replaces it.
                required { keys.adopt(arg.choice.invite.key) }
                when (val enrolled = required { enroll(admitted) }) {
                    is EnrollOutcome.Enrolled -> required { joining.run(arg.id, enrolled.evidence, on) }
                    is EnrollOutcome.NotEnrolled -> required { statuses.join.done(arg.id, enrolled.answer) }
                }
            }
            is EnrollOutcome.NotEnrolled -> required { statuses.join.done(arg.id, admitted.answer) }
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
    private val downloads: DownloadController,
    private val config: MembershipProjection,
    private val statuses: MembershipStatuses,
) : BackgroundEntryPoint<SwitchRequest>(runtime) {
    override suspend fun run(arg: SwitchRequest, on: Run) = on.steps {
        ensure("answer") { statuses.switch.settle(arg.id, SwitchVerdict.Ask) }
        val facts = required { lookup.facts() }
        val holds = when (facts.needsReceivedCheck) {
            true -> required { downloads.holdsEverythingFor(config.joinedOrRead()) }
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
        ensure("answer") { statuses.settings.settle(arg.id, SettingsResult.NotCurrent) }
        membership(membership, Freshness.Cached) {
            active { joined ->
                when (val saved = required { reconfigure(joined, arg.choice) }) {
                    is ReconfigureOutcome.Saved -> required { settled.run(arg.id, saved.active, on) }
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
 * set, the photos an earlier install received — then the membership itself, and what follows. A join in progress is
 * held from the key on ([MembershipTransition]), so no sweep of a failed leave's leftovers takes what is the join's.
 */
class Joining(
    private val transition: MembershipTransition,
    private val joinUnion: JoinUnion,
    private val shareSet: ShareSetLoad,
    private val adoption: ReceivedPhotoAdoption,
    private val enter: EnterMembership,
    private val joined: AfterJoin,
    private val statuses: MembershipStatuses,
) {
    suspend fun run(id: RequestId, enrolled: Enrolled, on: Run) = on.steps {
        ensure("transition") { transition.end() }
        ensure("join union") { joinUnion.close() }
        required { transition.begin() }
        required { joinUnion.open(enrolled.membership.event.id) }
        // The share set before the membership: no cycle ever sees the new membership over the previous one's ledger.
        required { shareSet.load(enrolled.membership.event.id) }
        // The photos an earlier install received are recognised before any uploader sees the membership.
        required { adoption.adopt(enrolled.membership.asConfig()) }
        when (val entered = required { enter(enrolled) }) {
            is EnterOutcome.Entered -> required { joined.run(id, entered.active, on) }
            // Joined already, or nothing written — the server's enrolment stands: a retry enrols again.
            is EnterOutcome.NotEntered -> required { statuses.join.done(id, entered.answer) }
        }
    }
}

/** What follows entering a membership: the uploads, the counts, the album, the others' photos and the push. */
class AfterJoin(
    private val uploads: UploadTransitions,
    private val statusRefresh: StatusRefresh,
    private val album: EventAlbum,
    private val downloads: DownloadController,
    private val joinUnion: JoinUnion,
    private val push: PushRegistration,
    private val tokens: PushTokenSource,
    private val statuses: MembershipStatuses,
) {
    suspend fun run(id: RequestId, joined: Membership.Active, on: Run) = on.steps {
        isolated("uploads") { uploads.onJoin() }
        isolated("status") { statusRefresh.run() }
        required { album.run("join", joined, on) }
        // The union the adoption read in this same join, when it did: one union read per join.
        isolated("downloads") {
            downloads.reconcile(joined.event.id, UnionTrigger.JOIN, known = joinUnion.take(joined.event.id))
        }
        isolated("push") { push.reRegister(tokens) }
        required { statuses.join.done(id, JoinCommit.Committed) }
    }
}

/** What a committed leave stops — every step its own, so one that fails leaves the rest done. */
class Leaving(
    private val uploads: UploadTransitions,
    private val downloads: DownloadController,
    private val keys: EventKeys,
    private val deliver: DeliverPendingLeaves,
) {
    private val log = Log("Leaving")

    suspend fun run(left: LeftMembership, on: Run) = on.steps {
        required { log.i("left", "event" of left.event) }
        isolated("stop uploads") { uploads.onLeave() }
        isolated("stop downloads") { downloads.onLeaveOrSwitch() }
        isolated("forget key") { keys.forget() }
        detached { deliver() }
    }
}

/** What a saved settings change re-drives, before the member is told it is saved. */
class AfterSettings(
    private val statusRefresh: StatusRefresh,
    private val uploads: UploadTransitions,
    private val album: EventAlbum,
    private val downloads: DownloadController,
    private val statuses: MembershipStatuses,
) {
    suspend fun run(id: RequestId, saved: Membership.Active, on: Run) = on.steps {
        isolated("status") { statusRefresh.run() }
        // A kick whatever the direction: the cycle's policy decides what uploads; nothing in flight is cancelled.
        isolated("uploads") { uploads.onReconfigure() }
        required { album.run("reconfigure", saved, on) }
        when (saved.share.direction.includesDownload) {
            true -> detached { downloads.reconcile(saved.event.id, UnionTrigger.RECONFIGURE) }
            false -> isolated("stop downloads") { downloads.onLeaveOrSwitch() }
        }
        required { statuses.settings.done(id, SettingsResult.Saved) }
    }
}

/**
 * The event album a membership saves into, after a join or a settings change: made when the member wants one and the
 * grant allows it, and the photos already held gathered into it — each its own step, so one that fails leaves the other
 * done. [trigger] names what asked, for the gather's lines.
 */
class EventAlbum(
    private val albums: AlbumCoordinator,
    private val gather: AlbumGather,
    private val access: GalleryAccessState,
) {
    suspend fun run(trigger: String, membership: Membership.Active, on: Run) = on.steps {
        isolated("album") {
            albums.ensureAlbum(
                membership.event.id,
                membership.event.name.text,
                membership.share.saveToAlbum,
                access.usable,
            )
        }
        isolated("album gather") { gather.start(trigger, membership.event.id) }
    }
}

/**
 * What a leave that failed half-way may have left — a key, downloads, an upload registration — swept while no
 * membership is held, and never while a join is keeping its own.
 */
class SweepLeftovers(
    private val membership: MembershipBranch,
    private val keys: EventKeys,
    private val downloads: DownloadController,
    private val uploads: UploadTransitions,
    private val transition: MembershipTransition,
) {
    suspend fun run(on: Run) = on.steps {
        membership(membership, Freshness.Cached) {
            notJoined { none ->
                isolated("leftover key") { keys.forgetLeftover(none, transition) }
                isolated("leftover downloads") { downloads.stopLeftovers(none, transition) }
                isolated("leftover registration") { uploads.stopLeftovers(none, transition) }
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
class DeletedTransfers(private val uploads: UploadTransitions, private val downloads: DownloadController) {
    suspend fun stop(@Suppress("UNUSED_PARAMETER") deleted: Membership.Deleted, on: Run) = on.steps {
        isolated("stop downloads") { downloads.onLeaveOrSwitch() }
        isolated("stop uploads") { uploads.onLeave() }
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
    private val downloads: DownloadController,
    private val deliver: DeliverPendingLeaves,
    private val settlement: ShareSettlement,
    private val claim: ClaimStateCheck,
    private val stateRefresh: StateRefresh,
    private val received: ReceivedReport,
) {
    suspend fun run(kind: WakeKind, on: Run) = on.steps {
        when (kind.checksPhotos) {
            true -> membership(membership, Freshness.Cached) {
                active { joined -> isolated("photo check") { downloads.reconcileIfDue(joined.event.id) } }
            }
            false -> Unit
        }
        isolated("pending leaves") { deliver() }
        isolated("final share") { settlement.settle() }
        membership(membership, Freshness.Cached) {
            active { joined ->
                when (required { claim(joined, kind.boundsEventRead) }) {
                    CheckCadence.Due -> required { stateRefresh.run(on) }
                    CheckCadence.NotYet -> Unit
                }
            }
        }
        isolated("received report") { received.report() }
    }
}
