package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.EnrollFact
import app.snapsync.data.membership.EventJoin
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.event.KeyVerdict
import app.snapsync.domain.membership.Enrolled
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.KeptKey
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MintsEnrolled
import app.snapsync.domain.membership.Share
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict

/**
 * Enrol this device in the event a member chose to join, in two steps with the join's own between them: [admit] — the
 * chosen range obeys the share-range rule, the invite's key opens the event, this device holds no membership and can
 * name itself, asking the server nothing — then [invoke], the server's admission. Every road to a membership passes
 * here, so none can skip a rule; nothing is written on this device until the membership is entered.
 */
class EnrollDevice(
    private val membership: MembershipRepository,
    private val identity: DeviceIdentity,
    private val join: EventJoin,
    private val ids: EventKeyIds,
) {
    /** Whether [choice] may be enrolled — nothing is sent. */
    @EntryPointCall
    suspend fun admit(choice: JoinChoice): AdmitOutcome = when (
        val range = CaptureRange.of(choice.from, choice.until, choice.event.range.start, choice.event.range.end)
    ) {
        is CaptureRangeVerdict.Ok -> keyed(choice, Share(range.range, choice.direction, choice.saveToAlbum))
        // Refused, never clamped: no screen can produce one, so it is a bug, and moving it would join a range nobody
        // chose.
        CaptureRangeVerdict.Inverted, CaptureRangeVerdict.TooLong, CaptureRangeVerdict.OutsideEvent ->
            EnrollOutcome.InvalidRange
    }

    /** Enrol [admitted] at the server, once its key is [kept] — so a membership never names a key that is not kept. */
    @EntryPointCall
    @OptIn(MintsEnrolled::class)
    suspend operator fun invoke(
        admitted: Admitted,
        @Suppress("UNUSED_PARAMETER") kept: KeptKey,
    ): EnrollOutcome =
        when (val fact = join.enroll(admitted.membership.event.id, admitted.device)) {
            EnrollFact.Admitted -> EnrollOutcome.Enrolled(Enrolled(admitted.membership))
            EnrollFact.EventOver -> EnrollOutcome.EventOver
            EnrollFact.Unverified -> EnrollOutcome.Unverified
            is EnrollFact.Failed -> EnrollOutcome.Failed(fact.fault)
        }

    private suspend fun keyed(choice: JoinChoice, share: Share): AdmitOutcome =
        when (val verdict = choice.event.encryption.openedBy(choice.invite.key?.let(ids::identify))) {
            is KeyVerdict.Opens -> unjoined(Membership.Active(choice.event, share), verdict.keep)
            KeyVerdict.WrongKey -> EnrollOutcome.WrongLink
        }

    private suspend fun unjoined(active: Membership.Active, key: KeyToKeep): AdmitOutcome =
        when (val held = membership.read()) {
            is MembershipRead.NotJoined -> identified(active, key)
            is MembershipRead.Joined -> held(held.membership, active)
            // A membership this build refuses is no membership to keep: the join replaces it. Any other could not be
            // read now, and the join fails rather than guess.
            is MembershipRead.Unavailable -> if (held.refused) identified(active, key) else EnrollOutcome.Failed()
        }

    private fun held(held: Membership, wanted: Membership.Active): AdmitOutcome =
        when (held.event.id == wanted.event.id) {
            true -> EnrollOutcome.AlreadyJoined
            false -> EnrollOutcome.JoinedElsewhere
        }

    private suspend fun identified(active: Membership.Active, key: KeyToKeep): AdmitOutcome =
        when (val id = identity.id()) {
            is DeviceIdRead.Ok -> Admitted(active, id.value, key)
            DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> EnrollOutcome.Failed()
        }
}

/** Whether a join may be enrolled. */
sealed interface AdmitOutcome

/** [membership] may be enrolled, as [device], once [key] is kept: every rule held, and nothing sent yet. */
class Admitted internal constructor(val membership: Membership.Active, val device: DeviceId, val key: KeyToKeep) :
    AdmitOutcome

/** What an enrolment came to. */
sealed interface EnrollOutcome {
    class Enrolled(val evidence: app.snapsync.domain.membership.Enrolled) : EnrollOutcome

    /** Nothing was enrolled; [answer] is what the member is told. */
    sealed interface NotEnrolled : EnrollOutcome, AdmitOutcome {
        val answer: JoinCommit
    }

    /** This device is already a member of that event: nothing was sent. */
    data object AlreadyJoined : NotEnrolled {
        override val answer = JoinCommit.Committed
    }

    /** This device is a member of another event: nothing was sent — a switch leaves first. */
    data object JoinedElsewhere : NotEnrolled {
        override val answer = JoinCommit.Failed()
    }

    /** The event admits nobody any more. */
    data object EventOver : NotEnrolled {
        override val answer = JoinCommit.EventOver
    }

    /** The server accepted no credential of this device's. */
    data object Unverified : NotEnrolled {
        override val answer = JoinCommit.Unverified
    }

    /** The invite's key does not open the event. */
    data object WrongLink : NotEnrolled {
        override val answer = JoinCommit.WrongLink
    }

    /** The chosen range breaks the share-range rule. */
    data object InvalidRange : NotEnrolled {
        override val answer = JoinCommit.Failed()
    }

    /**
     * Not enrolled: the membership could not be read, this device could not name itself, or the server said no. [fault]
     * is how the service failed — `null` when it was not the cause.
     */
    data class Failed(val fault: ServiceFault? = null) : NotEnrolled {
        override val answer = JoinCommit.Failed(fault)
    }
}
