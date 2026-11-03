package app.snapsync.domain.receiving

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.device.DeviceId

/**
 * A photo by its SOURCE identity — the device that shared it and that device's asset id: stable across syncs and
 * events, so what was received once is recognised in every later read and in every other event that serves it.
 */
data class AssetRef(val sourceDeviceId: DeviceId, val sourceAssetId: AssetId)

/** One resource of a union photo, as the union serves it: where to fetch it, and what it is. */
class UnionResource(
    val key: String,
    val url: String,
    val role: String,
    val contentType: String,
    val originalFilename: String,
)

/** One complete photo the event's union serves: whose it is, when it was captured, and its resources. */
class UnionPhoto(val ref: AssetRef, val captured: CaptureDate, val resources: List<UnionResource>)

/**
 * One answer of the event's union (decision record `changes/incremental-union`, D3): its [photos] — all of them for a
 * whole read, only those gained after the position asked from for a delta — and the [position] it covers, which the
 * next delta starts from. [othersDeclared] is what the OTHER members declared to the event at this read (change
 * `count-declared-photos`), `null` when it is unknown.
 */
class UnionPage(val photos: List<UnionPhoto>, val position: Long, val othersDeclared: Long?) {
    /**
     * Whether this read moved what the store keeps of the event's union — its position past [stored] (none is the same
     * as position 0, no gain yet), or what the others declared off [storedOthers]: a read that served nothing new and
     * moved neither costs no durable commit.
     */
    fun moved(
        stored: Long?,
        storedOthers: Long?,
    ): Boolean = position != (stored ?: 0L) || othersDeclared != storedOthers
}

/**
 * The photos of a [UnionPage] this device receives: every photo another member shares ([all]) — this device's own
 * contribution is in its library already — and among them those not yet settled here ([open]): neither imported nor
 * judged unimportable, so a read plans them.
 */
class ForeignPhotos private constructor(val all: List<UnionPhoto>, val open: List<UnionPhoto>) {
    /** The refs of every foreign photo, settled ones included — what the event's union holds of the others. */
    val refs: List<AssetRef> get() = all.map { it.ref }

    companion object {
        /** The foreign photos of [photos] for the device [me], given the refs [settled] here. */
        fun of(photos: List<UnionPhoto>, me: DeviceId, settled: Set<AssetRef>): ForeignPhotos {
            val all = photos.filter { it.ref.sourceDeviceId != me }
            return ForeignPhotos(all, all.filter { it.ref !in settled })
        }
    }
}

/**
 * What a reconcile reads of the event's union (decision record `changes/incremental-union`, D6): a whole read for a
 * trigger that asks for one or when nothing was read before, a delta from the stored position otherwise — and nothing
 * at all when a push announced a position this device has already read.
 */
sealed interface UnionReadPlan {
    /** Every photo the union serves. */
    data object Whole : UnionReadPlan

    /** Only what the union gained after [from]. */
    class Since(val from: Long) : UnionReadPlan

    /** The announced position is already read: nothing new to read. */
    data object AlreadyRead : UnionReadPlan

    companion object {
        /** The read for a trigger asking for a whole one when [whole], from the [stored] and [announced] positions. */
        fun of(whole: Boolean, stored: Long?, announced: Long?): UnionReadPlan = when (stored) {
            null -> Whole
            else -> from(whole, stored, announced)
        }

        private fun from(whole: Boolean, stored: Long, announced: Long?): UnionReadPlan = when {
            announced != null && stored >= announced -> AlreadyRead
            whole -> Whole
            else -> Since(stored)
        }
    }
}

/**
 * Why the app reads the union (decision record `changes/incremental-union`, D5–D6) — what it tells the backend, which
 * records it, and whether the read is [whole]. A push and a background wake read only what is new; every other reason
 * reads everything, because someone waits on the answer or a decision rests on it.
 */
enum class UnionTrigger(val whole: Boolean) {
    /** A silent push announced a photo. */
    PUSH(whole = false),

    /** A background wake with no push behind it. */
    WAKE(whole = false),

    /** The app came to the foreground: the member is looking, and a whole read heals anything a delta missed. */
    FOREGROUND(whole = true),

    /** Joining an event. */
    JOIN(whole = true),

    /** Photo access became usable: the received photos already in the library are recognised against all of it. */
    GRANT(whole = true),

    /** A settings change began receiving, with no position for a membership that never received. */
    RECONFIGURE(whole = true),

    /** Whether a leaving member had everything — a doubt is a no. */
    LEAVE_CHECK(whole = true),

    /** Up to which position this device holds every photo of the others — what it reports. */
    RECEIVED_CHECK(whole = true),
}

/**
 * What the OTHER members declared to an event at a read (change `count-declared-photos`): its declared total less what
 * this device had declared then. Unknown — a backend that answers no total, an own count that cannot be read — is
 * none, which awaits nothing. Clamped at 0: a publish crossing the read can make the subtraction briefly short.
 */
object OthersDeclared {
    fun of(total: Long?, own: Int?): Long? = total?.let { all -> own?.let { (all - it).coerceAtLeast(0L) } }
}
