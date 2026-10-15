package app.snapsync.data.membership

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.event.Event
import app.snapsync.domain.membership.ConfirmedDeletion
import app.snapsync.domain.membership.Enrolled
import app.snapsync.domain.membership.LeftMembership
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.Share
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.DbResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * **This device's membership** (`docs/architecture.md`, "Storage") — the `membership` row of the shared database, and
 * every write that changes it. Both processes read it; the app writes it.
 *
 * Reinstalling is leaving: the database dies with the install, so a reinstalled device reads not joined and rejoins
 * only by opening the invite again.
 *
 * Every write after the entry is a **compare-and-set**: it names the membership it was decided for, and changes the
 * row only while it still holds that event, not deleted — a decision taken over a membership a switch, a leave or a
 * deletion replaced meanwhile changes nothing and says so. A leave is **one transaction**: the row, the leave owed to
 * the backend, and the event's check times, together or not at all. So is a share change and the manifest version.
 *
 * [state] is seeded by its first use — never at construction, so building the composition opens no database — and
 * re-read by [reload] at every entry: another process's commit is not otherwise seen here.
 */
class MembershipRepository(private val db: SharedDatabase) {

    private val held = lazy { MutableStateFlow(read()) }

    /** The membership as last read conclusively — [MembershipRead.Unavailable] only until a read was. */
    val state: StateFlow<MembershipRead> get() = held.value

    /** The membership as the store holds it now. Never raises. */
    fun read(): MembershipRead = when (val read = db.execute { it.membershipRead() }) {
        is DbResult.Ok -> read.value
        is DbResult.Failed -> MembershipRead.Unavailable("the shared database is unreadable: ${read.detail}")
    }.also(::logged)

    /**
     * Re-read the membership into [state]. A read that could not be made keeps the last conclusive one: at an entry's
     * cadence a passing failure would otherwise drop a good membership from the screen.
     */
    fun reload() {
        held.value.value = afterReload(held.value.value, read())
    }

    /** Enter [enrolled]'s membership — only while this device holds none. */
    fun enter(enrolled: Enrolled): EnterResult =
        written<EnterResult>(
            db.execute { it.transactionWithResult { it.enter(enrolled.membership) } },
            EnterResult::Failed,
        )

    /** Mark [active] deleted, as [evidence] proves it is — while it is still the active membership. */
    fun markDeleted(active: Membership.Active, evidence: ConfirmedDeletion): MarkResult =
        written<MarkResult>(
            db.execute { it.transactionWithResult { it.markDeleted(evidence.event, active) } },
            MarkResult::Failed,
        )

    /** Fold [event] — the server's facts of [active]'s event — into it, while it is still the active membership. */
    fun update(active: Membership.Active, event: Event): UpdateResult = written<UpdateResult>(
        db.execute { it.transactionWithResult { it.update(Membership.Active(event, active.share)) } },
        UpdateResult::Failed,
    )

    /**
     * Replace [active]'s share with [share] and advance the manifest version — in ONE transaction, so a share that
     * landed is always one a cycle publishes, and one that did not changed nothing.
     */
    fun reconfigure(active: Membership.Active, share: Share): UpdateResult = written<UpdateResult>(
        db.execute { it.transactionWithResult { it.reconfigure(Membership.Active(active.event, share)) } },
        UpdateResult::Failed,
    )

    /**
     * Leave the membership held: in ONE transaction the membership goes, the leave is recorded as owed to the backend,
     * and the event's check times are forgotten — or nothing happens and the member stays fully joined.
     */
    fun leave(): LeaveResult = written<LeaveResult>(
        db.execute { it.transactionWithResult { it.leave() } },
        LeaveResult::Failed,
    )

    /**
     * Forget the membership held, locally — owing the backend no leave, and its check times with it: what a device that
     * starts from nothing does, its membership belonging to a backend it no longer talks to.
     */
    fun forget(): StoreWrite = written<StoreWrite>(
        db.execute { it.transactionWithResult { it.forget() } },
        StoreWrite::Failed,
    )

    /** A write's answer — [failed] with the store's detail when it could not be made; after any other, a re-read. */
    private fun <T> written(result: DbResult<T>, failed: (String) -> T): T = when (result) {
        is DbResult.Ok -> result.value.also { reload() }
        is DbResult.Failed -> failed(result.detail)
    }
}

private val log = Log("MembershipRepository")

private fun logged(read: MembershipRead) = when (read) {
    is MembershipRead.Unavailable ->
        log.unlabelled.w("membership unreadable — never \"not joined\"", "detail" of read.detail)
    is MembershipRead.Joined, is MembershipRead.NotJoined -> Unit
}

/** What a reload keeps: a conclusive read, or — when the fresh one is not — the last conclusive one. */
private fun afterReload(last: MembershipRead, fresh: MembershipRead): MembershipRead = when (fresh) {
    is MembershipRead.Joined, is MembershipRead.NotJoined -> fresh
    is MembershipRead.Unavailable -> kept(last, fresh)
}

private fun kept(last: MembershipRead, fresh: MembershipRead.Unavailable): MembershipRead = when (last) {
    is MembershipRead.Joined, is MembershipRead.NotJoined -> last
    is MembershipRead.Unavailable -> fresh
}

/** What [MembershipRepository.enter] did. */
sealed interface EnterResult {
    data class Entered(val active: Membership.Active) : EnterResult

    /** This device is already a member of that event: nothing was written. */
    data object AlreadyJoined : EnterResult

    /** This device is a member of another event: nothing was written — a switch leaves first. */
    data object JoinedElsewhere : EnterResult

    data class Failed(val detail: String) : EnterResult
}

/** What [MembershipRepository.markDeleted] did. */
sealed interface MarkResult {
    data class Marked(val deleted: Membership.Deleted) : MarkResult

    /** The membership was no longer the active one: nothing was written. */
    data object Superseded : MarkResult

    data class Failed(val detail: String) : MarkResult
}

/** What [MembershipRepository.update] or [MembershipRepository.reconfigure] did. */
sealed interface UpdateResult {
    data class Updated(val active: Membership.Active) : UpdateResult

    /** The membership was no longer the active one: nothing was written. */
    data object Superseded : UpdateResult

    data class Failed(val detail: String) : UpdateResult
}

/** What [MembershipRepository.leave] did. */
sealed interface LeaveResult {
    class Left(val evidence: LeftMembership) : LeaveResult

    /** No membership was held: nothing was written. */
    data object NotJoined : LeaveResult

    /** Nothing was written: the member is fully joined. */
    data class Failed(val detail: String) : LeaveResult
}
