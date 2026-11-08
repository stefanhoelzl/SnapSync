package app.snapsync.format

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.time.Timestamp
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * **The membership as 0.4 kept it** — `eventconfig.json` in the shared area — read by the one-time copy of an updated
 * device's file into the shared database (`LegacyRecords`; a shim, `PENDING_CLEANUPS.md`), and gone with it.
 *
 * The file is an **envelope**, `{"v": <version>, "payload": <the membership>}`, which exists for one reason: a build
 * that cannot read a file must land on *unreadable*, never *absent*, because absent reads as a leave. So:
 * - `v == 1`, its payload reads → [LegacyMembershipDecode.Valid].
 * - `v == 1`, no payload, or one that does not read (a missing field, a value that names nothing) →
 *   [LegacyMembershipDecode.Unusable]: left in place, never a leave.
 * - any other `v`, or text that is no envelope → [LegacyMembershipDecode.Foreign]: only content this build can
 *   **positively** interpret may drive a decision.
 *
 * Unknown keys are ignored on both the envelope and the payload; a field a later build added has its default. Total:
 * never throws.
 */
object LegacyMembershipText {
    /** The envelope version this build reads. */
    const val VERSION: Int = 1

    /** What the file's [bytes] hold, per the version table above: bytes that are no UTF-8 text are no envelope. */
    fun decode(bytes: ByteArray): LegacyMembershipDecode = when (val text = textOf(bytes)) {
        null -> LegacyMembershipDecode.Foreign("not text")
        else -> decode(text)
    }

    /** What [text] holds, per the version table above. */
    fun decode(text: String): LegacyMembershipDecode {
        val envelope = parsed(text) as? JsonObject ?: return LegacyMembershipDecode.Foreign("not a config envelope")
        val version = (envelope["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: return LegacyMembershipDecode.Foreign("not a config envelope")
        if (version != VERSION) {
            return LegacyMembershipDecode.Foreign("envelope version $version; this build reads $VERSION")
        }
        val payload = envelope["payload"] as? JsonObject ?: return LegacyMembershipDecode.Unusable
        return membership(payload)?.let(LegacyMembershipDecode::Valid) ?: LegacyMembershipDecode.Unusable
    }

    private fun textOf(bytes: ByteArray): String? = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }

    private fun parsed(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }

    private fun membership(payload: JsonObject): LegacyMembership? {
        val read = Fields(payload)
        val event = event(read) ?: return null
        val choices = choices(read) ?: return null
        val served = served(read) ?: return null
        return LegacyMembership(
            eventId = event.id,
            name = event.name,
            minPhotoDate = CaptureCutoff(event.cutoff),
            startsAt = EventStart(event.start),
            endsAt = EventEnd(event.end),
            maxPhotoDate = CaptureCeiling(event.ceiling),
            deletesAt = DeletesAt(event.deletes),
            direction = choices.direction,
            saveToAlbum = choices.saveToAlbum,
            members = served.members.value,
            openUntil = served.openUntil.value,
            deleted = choices.deleted,
            keyId = served.keyId.value,
        )
    }

    /** The joined event and its dates — each required but the start, which defaults to the cutoff. */
    private fun event(read: Fields): Event? {
        val cutoff = read.timestamp("minPhotoDate") ?: return null
        return Event(
            id = read.string("eventId")?.let(EventId::parse) ?: return null,
            name = read.string("name") ?: return null,
            cutoff = cutoff,
            start = read.optional("startsAt", cutoff) { timestampOf(it) } ?: return null,
            end = read.timestamp("endsAt") ?: return null,
            ceiling = read.timestamp("maxPhotoDate") ?: return null,
            deletes = read.timestamp("deletesAt") ?: return null,
        )
    }

    /** This device's choices, each with the default a file written before it existed meant. */
    private fun choices(read: Fields): Choices? = Choices(
        direction = read.optional("direction", Direction.Both) { directionOf(it) } ?: return null,
        saveToAlbum = read.optional("saveToAlbum", false) { booleanOf(it) } ?: return null,
        deleted = read.optional("deleted", false) { booleanOf(it) } ?: return null,
    )

    /** What was last served of the event, each `null` until it was. */
    private fun served(read: Fields): Served? = Served(
        members = read.nullable("members") { countsOf(it) } ?: return null,
        openUntil = read.nullable("openUntil") { openUntilOf(it) } ?: return null,
        keyId = read.nullable("keyId") { stringOf(it) } ?: return null,
    )

    private class Event(
        val id: EventId,
        val name: String,
        val cutoff: Timestamp,
        val start: Timestamp,
        val end: Timestamp,
        val ceiling: Timestamp,
        val deletes: Timestamp,
    )

    private class Choices(val direction: Direction, val saveToAlbum: Boolean, val deleted: Boolean)

    private class Served(
        val members: Nullable<LegacyMemberCounts>,
        val openUntil: Nullable<OpenUntil>,
        val keyId: Nullable<String>,
    )

    private fun countsOf(element: JsonElement): LegacyMemberCounts? {
        val counts = element as? JsonObject ?: return null
        val waiting = when (val value = counts["waiting"]) {
            null, JsonNull -> null
            else -> intOf(value) ?: return null
        }
        return LegacyMemberCounts(
            active = counts["active"]?.let(::intOf) ?: return null,
            settled = counts["settled"]?.let(::intOf) ?: return null,
            waiting = waiting,
        )
    }

    private fun stringOf(element: JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun timestampOf(element: JsonElement): Timestamp? = stringOf(element)?.let(TimestampText::parseOrNull)

    private fun openUntilOf(element: JsonElement): OpenUntil? = timestampOf(element)?.let(::OpenUntil)

    private fun booleanOf(element: JsonElement): Boolean? =
        (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun intOf(element: JsonElement): Int? = (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun directionOf(element: JsonElement): Direction? =
        stringOf(element)?.let { name -> Direction.entries.firstOrNull { it.name == name } }

    /**
     * A payload's fields as the legacy decoder read them: a missing field takes its default, a field that is there
     * must read — `null` from any reader means the payload does not read.
     */
    private class Fields(private val payload: JsonObject) {
        fun string(key: String): String? = payload[key]?.let(::stringOf)

        fun timestamp(key: String): Timestamp? = payload[key]?.let(::timestampOf)

        /** [key]'s value, [default] when it is missing; `null` when it is there and does not read (`null` included). */
        fun <T : Any> optional(key: String, default: T, read: (JsonElement) -> T?): T? =
            when (val value = payload[key]) {
                null -> default
                else -> read(value)
            }

        /**
         * [key]'s value, a [Nullable] `null` when it is missing or `null`; `null` when it is there and does not read.
         */
        fun <T : Any> nullable(key: String, read: (JsonElement) -> T?): Nullable<T>? = when (val value = payload[key]) {
            null, JsonNull -> Nullable(null)
            else -> read(value)?.let { Nullable(it) }
        }
    }

    /** A field that may hold nothing, read: [value] is `null` when the file said so. */
    private class Nullable<T : Any>(val value: T?)
}

/** What [LegacyMembershipText.decode] found. */
sealed interface LegacyMembershipDecode {
    /** The envelope is this build's version and its payload reads. */
    data class Valid(val membership: LegacyMembership) : LegacyMembershipDecode

    /** The envelope is this build's version but carries no usable membership: **unreadable**, never a leave. */
    data object Unusable : LegacyMembershipDecode

    /** The file exists but this build cannot positively interpret it: **unreadable**, never a leave. */
    data class Foreign(val reason: String) : LegacyMembershipDecode
}

/**
 * The membership a 0.4 file held, as values: the joined event, its name and dates, this device's choices, and what was
 * last served of it. Defaults are what a file written before a field existed meant.
 */
data class LegacyMembership(
    val eventId: EventId,
    val name: String,
    val minPhotoDate: CaptureCutoff,
    val startsAt: EventStart = EventStart(minPhotoDate.at),
    val endsAt: EventEnd,
    val maxPhotoDate: CaptureCeiling,
    val deletesAt: DeletesAt,
    val direction: Direction = Direction.Both,
    val saveToAlbum: Boolean = false,
    val members: LegacyMemberCounts? = null,
    val openUntil: OpenUntil? = null,
    val deleted: Boolean = false,
    val keyId: String? = null,
)

/** The event's present members, how many settled, and — `null` from a file that predates it — how many still share. */
data class LegacyMemberCounts(val active: Int, val settled: Int, val waiting: Int? = null)
