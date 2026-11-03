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
 * How far this device holds the others' photos: the union position a whole read covers, when this device receives
 * nothing or every foreign photo it serves is settled here and nothing waits to download or import — what it reports,
 * so the event's photos can go once every member has them.
 */
sealed interface HeldPosition {
    /** Whether this device holds every photo it is owed — what a switch asks before leaving without a question. */
    val complete: Boolean

    class Holds(val position: Long) : HeldPosition {
        override val complete = true
    }

    /** Something is still to arrive: no position is reported. */
    data object Lacks : HeldPosition {
        override val complete = false
    }

    companion object {
        /** For a membership that does not receive: it holds everything it is owed at the position it read. */
        fun ofNonReceiving(position: Long): HeldPosition = Holds(position)

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

/** Whether a held position is worth telling: the backend keeps the highest, so only a higher one is sent. */
sealed interface ReportDue {
    class Report(val position: Long) : ReportDue

    data object Unchanged : ReportDue

    companion object {
        /** [found], against the [last] position the backend recorded in this process (`null`: none yet). */
        fun of(found: Long, last: Long?): ReportDue = when {
            last != null && found <= last -> Unchanged
            else -> Report(found)
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
