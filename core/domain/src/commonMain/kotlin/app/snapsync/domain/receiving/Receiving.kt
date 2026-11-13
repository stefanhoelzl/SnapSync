package app.snapsync.domain.receiving

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventRange
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.time.Timestamp
import kotlin.jvm.JvmInline

// Receiving's evidence (`docs/patterns.md`, "Evidence types"): a step that needs a verdict to have said yes requires
// its evidence, and each has one mint, its constructor's opt-in pinned to that one site (`OptInPinsTest`). Never
// stored, never serialized, never a data class.

/** Only [ReceivingVerdict] mints a [ReceivingMembership]. */
@RequiresOptIn("Only ReceivingVerdict says this membership receives.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsReceivingMembership

/** Only the download store's recognition read mints a [Recognised]. */
@RequiresOptIn(
    "Only the download store's read says a membership's received photos are recognised.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsRecognised

/**
 * [membership] receives the others' photos, and this device holds its event's key: what planning, downloading and
 * importing require — a share-only member cannot plan, enqueue or import by construction.
 */
@JvmInline
value class ReceivingMembership @MintsReceivingMembership constructor(val membership: Membership.Active)

/**
 * [event]'s received photos have been recognised (`changes/archive/2026-10-11-close-share-back-gaps`): a pass READ the
 * event's union and recorded every received photo the library still holds. What an import requires — an import that
 * overtakes the recognition lands a second copy of a photo the library holds — and what every uploader waits for.
 */
@JvmInline
value class Recognised @MintsRecognised constructor(val event: EventId)

/** Whether the joined membership receives — by its direction and its event's key. */
sealed interface ReceivingVerdict {
    class Receives(val receiving: ReceivingMembership) : ReceivingVerdict

    /** Nothing is planned for the membership, for one of two reasons a caller tells apart only when it must. */
    sealed interface NotReceiving : ReceivingVerdict

    /** The membership shares only: nothing is planned, downloaded or imported for it. */
    data object SharesOnly : NotReceiving

    /**
     * The event's key is lost: nothing downloads until its invite gives it back. Never "nothing to receive", so one
     * who lost the key is never taken for a member who holds everything.
     */
    data object KeyLost : NotReceiving

    companion object {
        fun of(active: Membership.Active, key: KeyPresence): ReceivingVerdict =
            when (active.share.direction.includesDownload) {
                true -> keyed(active, key)
                false -> SharesOnly
            }

        @OptIn(MintsReceivingMembership::class)
        private fun keyed(active: Membership.Active, key: KeyPresence): ReceivingVerdict = when (key) {
            KeyPresence.Lost -> KeyLost
            KeyPresence.NotNeeded, KeyPresence.Unknown, is KeyPresence.Held -> Receives(ReceivingMembership(active))
        }
    }
}

/**
 * A photo already in the library that a recognition pass found, by its SnapSync mark, to be [ref]'s earlier import:
 * [localId] is that library asset, [captured] the union's capture date for the ref.
 */
class AdoptedPhoto(val ref: AssetRef, val localId: AssetId, val captured: CaptureDate)

/**
 * Where a recognition pass looks for marked photos: from the earliest to the latest capture date among the event's
 * open foreign photos, and never narrower than the event's own range. A member's range may reach outside the event's
 * dates, so the event's range alone would miss a received photo another member took the evening before. An undated
 * photo widens nothing.
 */
class RecognitionWindow private constructor(val from: Timestamp, val until: Timestamp) {
    companion object {
        fun of(range: EventRange, open: List<CaptureDate>): RecognitionWindow {
            val dates = open.filterIsInstance<CaptureDate.Dated>().map { it.at }
            return RecognitionWindow(
                dates.fold(range.start.at, ::minOf),
                dates.fold(range.end.at, ::maxOf),
            )
        }
    }
}

/**
 * How far this device holds the others' photos: nothing is owed to a membership that receives nothing; one that
 * receives holds the union position a whole read covers when every foreign photo it serves is settled here and nothing
 * waits to download or import — what it reports, so the event's photos can go once every member has them.
 */
sealed interface HeldPosition {
    /** Whether this device holds every photo it is owed — what a switch asks before leaving without a question. */
    val complete: Boolean

    /** What the event is told of it ([ReceivedReport]). */
    val report: ReceivedReport

    class Holds(val position: Long) : HeldPosition {
        override val complete = true
        override val report: ReceivedReport get() = ReceivedReport.Position(position)
    }

    /**
     * Something is still to arrive. It is still told — as position `0`, which never moves a recorded position back — so
     * a device that once said it receives nothing and receives again is never taken for one that is done.
     */
    data object Lacks : HeldPosition {
        override val complete = false
        override val report: ReceivedReport get() = ReceivedReport.Position(0)
    }

    /** The membership shares only: no photo of the others is owed to it, whatever they add later. */
    data object ReceivesNothing : HeldPosition {
        override val complete = true
        override val report: ReceivedReport get() = ReceivedReport.ReceivesNothing
    }

    companion object {
        /**
         * For one that receives: [settled] of the [foreign] photos the read served are settled here, and [waiting] says
         * whether anything still waits to download or import.
         */
        fun of(position: Long, foreign: Int, settled: Int, waiting: Boolean): HeldPosition = when {
            settled == foreign && !waiting -> Holds(position)
            else -> Lacks
        }
    }
}

/**
 * What a device tells its event it received (`reportReceived`): the union [Position] up to which it holds every photo
 * of the others, or that it [ReceivesNothing] at all — which alone is the received half of being done, so a share-only
 * phone that settled early and went off holds up nobody. Every report rewrites the second: a position says the device
 * receives again.
 */
sealed interface ReceivedReport {
    class Position(val position: Long) : ReceivedReport

    data object ReceivesNothing : ReceivedReport

    companion object {
        /**
         * What report is due at the end of a wake: a share-only membership tells it at once — it holds up the event's
         * end otherwise — and one that receives tells its position once its event's range has [ended]. Before that, a
         * receiving one with delivery facts [untold] tells only those: the operator learns when a photo reached the
         * library while the event runs, not after it (decision record `changes/delivery-record`, D3).
         */
        fun dueFor(receiving: ReceivingVerdict, ended: Boolean, untold: Boolean): ReportScope = when (receiving) {
            ReceivingVerdict.SharesOnly -> ReportScope.HELD
            is ReceivingVerdict.Receives, ReceivingVerdict.KeyLost -> when {
                ended -> ReportScope.HELD
                untold -> ReportScope.FACTS
                else -> ReportScope.NONE
            }
        }
    }
}

/** What a wake's end tells its event of what this device received ([ReceivedReport.dueFor]). */
enum class ReportScope {
    /** Nothing. */
    NONE,

    /** How far this device holds the others' photos — the union read that judges it first. */
    HELD,

    /**
     * Only the untold delivery facts, under position `0`: it claims nothing, so it never moves a recorded position
     * back, and it reads no union to say so.
     */
    FACTS,
}

/**
 * Whether a report is worth sending: the backend keeps the highest position and the latest word on receiving nothing,
 * so only a higher position, or a change of word, is sent — or any report while delivery facts wait to be told
 * (decision record `changes/delivery-record`, D3), which would otherwise wait for the next new photo.
 */
sealed interface ReportDue {
    class Report(val report: ReceivedReport) : ReportDue

    data object Unchanged : ReportDue

    companion object {
        /**
         * [found], against the [last] report the backend recorded in this process (`null`: none yet), and whether
         * delivery facts are [untold].
         */
        fun of(found: ReceivedReport, last: ReceivedReport?, untold: Boolean): ReportDue = when (untold) {
            true -> Report(found)
            false -> of(found, last)
        }

        private fun of(found: ReceivedReport, last: ReceivedReport?): ReportDue = when (found) {
            is ReceivedReport.Position -> positioned(found, last)
            ReceivedReport.ReceivesNothing -> when (last) {
                ReceivedReport.ReceivesNothing -> Unchanged
                is ReceivedReport.Position, null -> Report(found)
            }
        }

        private fun positioned(found: ReceivedReport.Position, last: ReceivedReport?): ReportDue = when (last) {
            is ReceivedReport.Position -> when (found.position > last.position) {
                true -> Report(found)
                false -> Unchanged
            }
            ReceivedReport.ReceivesNothing, null -> Report(found)
        }
    }
}

/**
 * Which of the library's marked photos are which open photos' earlier imports: each marked photo whose token names
 * exactly one of the [open] photos is adopted as that photo's. A token two open photos share is ambiguous: neither is
 * adopted, and each downloads as it would have. Two library photos carrying one token (a duplicate import) adopt one.
 */
object RecognitionMatch {
    fun of(open: List<UnionPhoto>, marked: Map<String, AssetId>): List<AdoptedPhoto> {
        val byToken = open.groupBy {
            ReceivedPhotoName.token(
                it.ref,
            )
        }.filterValues { it.size == 1 }.mapValues { it.value[0] }
        return marked.mapNotNull { (token, localId) ->
            byToken[token]?.let { only -> AdoptedPhoto(only.ref, localId, only.captured) }
        }
    }
}

/**
 * How many of the others' declared photos are still awaited here (change `count-declared-photos`): what they declared
 * at the last union read less every foreign photo this device already has a row for ([known]). Only a member who
 * [receives] awaits anything — one who does not is never shown an arrow for photos it will not fetch — and an unknown
 * declaration is 0, which reads exactly as before the count. Clamped at 0: a backend answer crossing the read can make
 * the subtraction briefly short, and the next read corrects it.
 */
object AwaitedPhotos {
    fun of(receives: Boolean, othersDeclared: Long?, known: Int): Int = when (receives) {
        true -> othersDeclared?.let { (it - known).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() } ?: 0
        false -> 0
    }
}
