package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.EventUnion
import app.snapsync.data.receiving.HandlesRead
import app.snapsync.data.receiving.MarkedPhotos
import app.snapsync.data.receiving.MarkedRead
import app.snapsync.data.receiving.ReceivedPhotos
import app.snapsync.data.receiving.RecognitionPasses
import app.snapsync.data.receiving.RecognitionRead
import app.snapsync.data.receiving.RecognitionRecords
import app.snapsync.data.receiving.UnionRead
import app.snapsync.data.sharing.SelectionState
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.receiving.ForeignPhotos
import app.snapsync.domain.receiving.Recognised
import app.snapsync.domain.receiving.RecognitionMatch
import app.snapsync.domain.receiving.RecognitionWindow
import app.snapsync.domain.receiving.UnionPage
import app.snapsync.domain.receiving.UnionReadPlan
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.inbound.Field
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Recognise the photos an earlier install received for [Membership.Active]'s event — a deleted received photo never
 * comes back, and received photos are never shared back: the photos still in the library that carry a SnapSync mark
 * ([app.snapsync.domain.receiving.ReceivedPhotoName]) are recorded as their refs' confirmed imports, so they are
 * neither received again nor shared as the member's own. It runs for EVERY direction: a share-only member can share
 * a received photo back just as well.
 *
 * Deleting the app deletes the download store, the only record of which library photos came from SnapSync; the
 * library is not deleted. A pass that READ the event's whole union records the membership as recognised, which every
 * uploader and every import waits for; one that could not read it records nothing, and the next pass asks again.
 */
class RecognizeReceivedPhotos(
    private val records: RecognitionRecords,
    private val union: EventUnion,
    private val photos: ReceivedPhotos,
    private val marked: MarkedPhotos,
    private val identity: DeviceIdentity,
    private val passes: RecognitionPasses,
) {
    private val log = Log("RecognizeReceivedPhotos")

    /** The join surface waits on the first pass: the same bound as the join's own listing. */
    private val unionTimeout = 15.seconds

    /** One pass for [active], reading for [trigger]. Answers the union it read, which a join plans from too. */
    @EntryPointCall
    suspend operator fun invoke(active: Membership.Active, trigger: UnionTrigger): RecognitionOutcome =
        logged(
            when (val page = read(active, trigger)) {
                null -> Ended.NoUnion
                else -> Pass(active, page).run()
            },
        )

    /** The whole union, read within [unionTimeout] — the join surface waits on it — or `null`. */
    private suspend fun read(active: Membership.Active, trigger: UnionTrigger): UnionPage? =
        (
            withTimeoutOrNull(
                unionTimeout,
            ) { union.read(active.event.id, UnionReadPlan.Whole, trigger) } as? UnionRead.Ok
            )
            ?.page

    /** One pass of [active] over [page]. */
    private inner class Pass(private val active: Membership.Active, private val page: UnionPage) {
        suspend fun run(): Ended = when (val me = identity.id()) {
            is DeviceIdRead.Ok -> settledFor(me.value)
            DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> Ended.Failed(page, "this device's id: $me")
        }

        private suspend fun settledFor(me: DeviceId): Ended = when (
            val settled = records.settled(
                page.photos.map { it.ref },
            )
        ) {
            is DownloadRead.Ok -> opened(ForeignPhotos.of(page.photos, me, settled.value))
            is DownloadRead.Unavailable -> Ended.Failed(page, settled.detail)
        }

        private suspend fun opened(foreign: ForeignPhotos): Ended = when (foreign.open.isEmpty()) {
            true -> recognised("foreign" of foreign.all.size)
            false -> known(foreign)
        }

        private suspend fun known(foreign: ForeignPhotos): Ended = when (val handles = photos.handles()) {
            is HandlesRead.Ok -> searched(foreign, marked.inWindow(window(foreign), handles.ids))
            is HandlesRead.Unavailable -> Ended.Failed(page, handles.detail)
        }

        private suspend fun searched(foreign: ForeignPhotos, found: MarkedRead): Ended = when (found) {
            is MarkedRead.Found -> adopted(
                records.adopt(active.event.id, RecognitionMatch.of(foreign.open, found.byToken)),
            )
            MarkedRead.NotReadable -> Ended.LibraryUnreadable(page, foreign.open.size)
        }

        private suspend fun adopted(recorded: DownloadRead<Set<*>>): Ended = when (recorded) {
            is DownloadRead.Ok -> recognised("adopted" of recorded.value.size)
            is DownloadRead.Unavailable -> Ended.Failed(page, recorded.detail)
        }

        private suspend fun recognised(count: Field): Ended = when (
            val written = photos.markRecognised(
                active.event.id,
            )
        ) {
            StoreWrite.Written -> Ended.Recognised(page, count).also { passes.ran(active.event.id) }
            is StoreWrite.Failed -> Ended.Failed(page, written.detail)
        }

        private fun window(foreign: ForeignPhotos) = RecognitionWindow.of(
            active.event.range,
            foreign.open.map { it.captured },
        )
    }

    /** How a pass ended, and what its line says. */
    private sealed interface Ended {
        suspend fun reported(log: Log): RecognitionOutcome

        data object NoUnion : Ended {
            override suspend fun reported(log: Log): RecognitionOutcome {
                log.w("the union is unavailable: nothing recognised; sharing and receiving wait for the next pass")
                return RecognitionOutcome.UnionUnavailable
            }
        }

        class Recognised(val page: UnionPage, val count: Field) : Ended {
            override suspend fun reported(log: Log): RecognitionOutcome {
                log.i("recognised", count)
                return RecognitionOutcome.Recognised(page)
            }
        }

        class LibraryUnreadable(val page: UnionPage, val open: Int) : Ended {
            override suspend fun reported(log: Log): RecognitionOutcome {
                log.i("the library is not readable yet: recognition waits for a usable grant", "open" of open)
                return RecognitionOutcome.LibraryUnreadable(page)
            }
        }

        class Failed(val page: UnionPage, val why: String) : Ended {
            override suspend fun reported(log: Log): RecognitionOutcome {
                log.w("nothing recognised: the store or this device's id cannot be read", "why" of why)
                return RecognitionOutcome.Unavailable(page)
            }
        }
    }

    private suspend fun logged(ended: Ended): RecognitionOutcome = ended.reported(log)
}

/** What a recognition pass came to — with the union it read, when it read one. */
sealed interface RecognitionOutcome {
    /** The union the pass read, when it read one — which a join plans from, so a join reads the union once. */
    val read: UnionPage?

    class Recognised(val page: UnionPage) : RecognitionOutcome {
        override val read: UnionPage get() = page
    }

    /** The library could not be read: the membership stays unrecognised until a grant lets a pass read it. */
    class LibraryUnreadable(val page: UnionPage) : RecognitionOutcome {
        override val read: UnionPage get() = page
    }

    data object UnionUnavailable : RecognitionOutcome {
        override val read: UnionPage? get() = null
    }

    class Unavailable(val page: UnionPage) : RecognitionOutcome {
        override val read: UnionPage get() = page
    }
}

/** Why a recognition pass is asked for outside a join. */
enum class RecognitionAsk {
    /** Before a drain imports, or once a grant became usable: unless a pass read this membership's library here. */
    BEFORE_IMPORTING,

    /** After a selection was published: unless one did, or the store still holds the membership recognised. */
    AFTER_SELECTION,
}

/** Whether a recognition pass is due, for [RecognitionAsk]'s reason. */
class RecognitionNeeded(
    private val passes: RecognitionPasses,
    private val photos: ReceivedPhotos,
    private val grant: GrantRead,
) {
    @EntryPointCall
    suspend operator fun invoke(active: Membership.Active, ask: RecognitionAsk): Boolean = when (grant.access()) {
        PhotoAccess.FULL, PhotoAccess.LIMITED -> !passes.ranFor(active.event.id) && notRecorded(active, ask)
        PhotoAccess.NONE -> false
    }

    private suspend fun notRecorded(active: Membership.Active, ask: RecognitionAsk): Boolean = when (ask) {
        RecognitionAsk.BEFORE_IMPORTING -> true
        RecognitionAsk.AFTER_SELECTION -> photos.recognition(active.event.id) !is RecognitionRead.Recognised
    }
}

/**
 * A selection change's first step, BEFORE its snapshot is published: when it holds marked photos — of any capture
 * date — the store does not record, any of them may be a received photo no pass has recognised, so the membership's
 * recognition is withdrawn before any reader sees the snapshot, and the next pass recognises them. A read of memory and
 * the store only.
 */
class WithholdRecognition(
    private val marked: MarkedPhotos,
    private val photos: ReceivedPhotos,
    private val passes: RecognitionPasses,
) {
    private val log = Log("WithholdRecognition")

    /** Withhold [membership]'s recognition when [selection] needs it — nothing for a device in no active event. */
    @EntryPointCall
    suspend operator fun invoke(membership: MembershipRead, selection: SelectionState.Read) = when (membership) {
        is MembershipRead.Joined -> joined(membership.membership, selection)
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> Unit
    }

    private suspend fun joined(membership: Membership, selection: SelectionState.Read) = when (membership) {
        is Membership.Active -> active(membership, selection)
        is Membership.Deleted -> Unit
    }

    private suspend fun active(active: Membership.Active, selection: SelectionState.Read) = when (
        val handles = photos.handles()
    ) {
        is HandlesRead.Ok -> withheld(active, marked.among(selection, handles.ids).size)
        // A store that cannot be read withholds too: sharing waits for a pass that can.
        is HandlesRead.Unavailable -> withheld(active, unknown = 1)
    }

    private suspend fun withheld(active: Membership.Active, unknown: Int) = when (unknown) {
        0 -> Unit
        else -> {
            photos.withdrawRecognition(active.event.id)
            passes.forget()
            log.i("a selection brought unrecognised marked photos: sharing waits for recognition", "photos" of unknown)
        }
    }
}

/**
 * A join's housekeeping, before its recognition pass: forget which received photos belonged to every other event and
 * their recognition (change `event-scoped-local-state`), and that any pass ran — a join's pass always runs.
 */
class ForgetOtherEvents(private val records: RecognitionRecords, private val passes: RecognitionPasses) {
    private val log = Log("ForgetOtherEvents")

    @EntryPointCall
    suspend operator fun invoke(active: Membership.Active) {
        passes.forget()
        when (val forgot = records.forgetOtherEvents(active.event.id)) {
            StoreWrite.Written -> Unit
            // Housekeeping only: every read names its event, so another event's refs count for nothing.
            is StoreWrite.Failed -> log.w("earlier events' received-photo refs stay, inert", "why" of forgot.detail)
        }
    }
}

/** Whether a drain may import now: a usable grant, and the membership's received photos recognised. */
class ImportReady(private val photos: ReceivedPhotos, private val grant: GrantRead) {
    private val log = Log("ImportReady")

    @EntryPointCall
    suspend operator fun invoke(active: Membership.Active): ImportReadiness = when (grant.access()) {
        PhotoAccess.FULL, PhotoAccess.LIMITED -> recognised(photos.recognition(active.event.id))
        PhotoAccess.NONE -> ImportReadiness.NotReady.also {
            log.i(
                "no usable photo grant: what is staged stays staged",
            )
        }
    }

    private suspend fun recognised(read: RecognitionRead): ImportReadiness = when (read) {
        is RecognitionRead.Recognised -> ImportReadiness.Ready(read.evidence)
        RecognitionRead.NotRecognised, is RecognitionRead.Unavailable -> ImportReadiness.NotReady.also {
            log.i("the received photos are not recognised yet: what is staged stays staged")
        }
    }
}

/** Whether a drain may import. */
sealed interface ImportReadiness {
    class Ready(val recognised: Recognised) : ImportReadiness

    data object NotReady : ImportReadiness
}
