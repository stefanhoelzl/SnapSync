package app.snapsync.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * The **date vocabulary**: one instant type plus a distinct type per *role* it can play, so a date can never be used
 * as a date it is not.
 *
 * Every date here is an instant to the second, and every role holds one, so as plain instants every one of them
 * would be assignable to every other. That is not a theoretical hazard; it is the bug this vocabulary exists to make
 * impossible:
 *
 * - `add-event-date-range` added the capture-date **ceiling** to the byte filter and the join preview but
 *   not to the manifest projection or the status total `N`, because `Contribution.Since -> c.cutoff`
 *   (dropping `until`) compiles exactly as readily as the correct destructure. Post-ceiling photos landed
 *   in `device.json` and in `N` while their bytes never uploaded, pegging the screen below 100% forever.
 * - The privacy-critical sibling: passing `startsAt` where `minPhotoDate` is wanted **lowers** the capture
 *   floor, which uploads photos the member excluded — silent, and in the direction the design forbids.
 *
 * With distinct role types both are **compile errors**. The friction is the point: writing
 * `creationDate >= cutoff.at` forces you to name which bound you meant.
 *
 * **A date compares as an instant**, never as text: an off-shape string (a fractional second, an offset) cannot sort
 * wrongly because no string is ever compared.
 *
 * **Serialization is transparent.** Each role serializes as the canonical UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` text of its
 * instant ([CaptureDate.iso]), so the wire payloads and the persisted `EventConfig` are byte-identical to before this
 * vocabulary existed — the backend is untouched and an already-persisted config still parses. A text that does not
 * parse is a decode failure. `EventConfigTest`/`EventDatesTest` pin that.
 */
@JvmInline
value class CaptureDate private constructor(val at: Instant) : Comparable<CaptureDate> {
    override fun compareTo(other: CaptureDate): Int = at.compareTo(other.at)

    /** The canonical UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` text — for logging and for the wire, never for a comparison. */
    val iso: String get() = canonicalText(at)

    override fun toString(): String = iso

    companion object {
        /** [instant] as a capture date: truncated to its second, toward the earlier instant. */
        fun of(instant: Instant): CaptureDate = CaptureDate(Instant.fromEpochSeconds(instant.epochSeconds))

        /** The capture date [text] names — any ISO-8601 instant — or `null` when it names none. */
        fun parseOrNull(text: String): CaptureDate? = Instant.parseOrNull(text)?.let(::of)

        /** The capture date [text] names; a text that names none is the caller's error. */
        fun parse(text: String): CaptureDate = of(Instant.parse(text))
    }
}

/**
 * A membership's capture-date **lower** bound: the earliest capture date this device contributes, already
 * clamped to `max(chosen, startsAt)` at join. Persisted as `EventConfig.minPhotoDate`.
 *
 * Distinct from [EventStart] on purpose — they are equal for a member who accepted the default, and
 * confusing them is how a floor gets silently lowered.
 */
@Serializable(with = CaptureCutoffSerializer::class)
@JvmInline
value class CaptureCutoff(val at: CaptureDate) : Comparable<CaptureCutoff> {
    override fun compareTo(other: CaptureCutoff): Int = at.compareTo(other.at)
    override fun toString(): String = at.iso
}

/**
 * A membership's capture-date **upper** bound (the ceiling): the latest capture date this device
 * contributes, already clamped to `min(chosen, endsAt)` at join. Persisted as `EventConfig.maxPhotoDate`.
 *
 * This is the bound `add-event-date-range` dropped at two of four consumers. It reaches every consumer now because they
 * all read the one admitted set.
 */
@Serializable(with = CaptureCeilingSerializer::class)
@JvmInline
value class CaptureCeiling(val at: CaptureDate) : Comparable<CaptureCeiling> {
    override fun compareTo(other: CaptureCeiling): Int = at.compareTo(other.at)
    override fun toString(): String = at.iso
}

/**
 * The **event's** start date — host-chosen at creation, immutable, shared by every member. It is the
 * *floor* a member's [CaptureCutoff] is clamped up to and the default that member sees; it is never itself
 * a membership's bound.
 */
@Serializable(with = EventStartSerializer::class)
@JvmInline
value class EventStart(val at: CaptureDate) : Comparable<EventStart> {
    override fun compareTo(other: EventStart): Int = at.compareTo(other.at)
    override fun toString(): String = at.iso
}

/**
 * The **event's** end date — host-chosen at creation, immutable. The *ceiling* a member's
 * [CaptureCeiling] is clamped down to, and what the "Event ended" status line compares against. It bounds
 * which photos may be uploaded and closes nothing: the event's lifetime is a separate, stamped value
 * ([DeletesAt]).
 */
@Serializable(with = EventEndSerializer::class)
@JvmInline
value class EventEnd(val at: CaptureDate) : Comparable<EventEnd> {
    override fun compareTo(other: EventEnd): Int = at.compareTo(other.at)
    override fun toString(): String = at.iso
}

/**
 * When the backend deletes the event's shared data — derived **server-side** from `max(createdAt, startsAt) + lifetime`
 * and served on the details response. The device stores it and never computes it.
 *
 * Its own type because it is the one date that decides whether a membership is **destroyed** (the offline second
 * witness of the self-leave) — the single most expensive role to confuse with any other.
 */
@Serializable(with = DeletesAtSerializer::class)
@JvmInline
value class DeletesAt(val at: CaptureDate) : Comparable<DeletesAt> {
    override fun compareTo(other: DeletesAt): Int = at.compareTo(other.at)
    override fun toString(): String = at.iso
}

/** The underlying canonical string of any capture-date role — for logging and wire encoding only. */
val CaptureCutoff.iso: String get() = at.iso

/** The underlying canonical string of any capture-date role — for logging and wire encoding only. */
val CaptureCeiling.iso: String get() = at.iso

/** The underlying canonical string of any capture-date role — for logging and wire encoding only. */
val EventStart.iso: String get() = at.iso

/** The underlying canonical string of any capture-date role — for logging and wire encoding only. */
val EventEnd.iso: String get() = at.iso

/** The underlying canonical string of any capture-date role — for logging and wire encoding only. */
val DeletesAt.iso: String get() = at.iso

/** Build a role from a raw canonical string — the one narrow door from the wire into the vocabulary. */
fun captureCutoff(iso: String): CaptureCutoff = CaptureCutoff(CaptureDate.parse(iso))

/** Build a role from a raw canonical string — the one narrow door from the wire into the vocabulary. */
fun captureCeiling(iso: String): CaptureCeiling = CaptureCeiling(CaptureDate.parse(iso))

/** Build a role from a raw canonical string — the one narrow door from the wire into the vocabulary. */
fun eventStart(iso: String): EventStart = EventStart(CaptureDate.parse(iso))

/** Build a role from a raw canonical string — the one narrow door from the wire into the vocabulary. */
fun eventEnd(iso: String): EventEnd = EventEnd(CaptureDate.parse(iso))

/** Build a role from a raw canonical string — the one narrow door from the wire into the vocabulary. */
fun deletesAt(iso: String): DeletesAt = DeletesAt(CaptureDate.parse(iso))

/**
 * Transparent `String` codecs. Written out rather than relying on the plugin's value-class inlining
 * because the persisted `EventConfig` and every wire payload depend on these staying bare strings — a
 * silent switch to an object wrapper would strand every joined device's membership.
 */
internal abstract class IsoSerializer<T>(
    name: String,
    private val wrap: (String) -> T,
    private val unwrap: (T) -> String,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(unwrap(value))
    override fun deserialize(decoder: Decoder): T = wrap(decoder.decodeString())
}

internal object CaptureCutoffSerializer :
    IsoSerializer<CaptureCutoff>("app.snapsync.model.CaptureCutoff", ::captureCutoff, { it.at.iso })

internal object CaptureCeilingSerializer :
    IsoSerializer<CaptureCeiling>("app.snapsync.model.CaptureCeiling", ::captureCeiling, { it.at.iso })

internal object EventStartSerializer :
    IsoSerializer<EventStart>("app.snapsync.model.EventStart", ::eventStart, { it.at.iso })

internal object EventEndSerializer :
    IsoSerializer<EventEnd>("app.snapsync.model.EventEnd", ::eventEnd, { it.at.iso })

internal object DeletesAtSerializer :
    IsoSerializer<DeletesAt>("app.snapsync.model.DeletesAt", ::deletesAt, { it.at.iso })
