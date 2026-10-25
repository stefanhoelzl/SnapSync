package app.snapsync.data.store

import app.cash.sqldelight.ColumnAdapter
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.time.Timestamp
import app.snapsync.format.TimestampText

// The domain's values as the text columns that hold them (`docs/patterns.md`, "Formats live at the boundary"): the one
// place each column's encoding lives, wired once per database.

/**
 * What an id column held, as it was read: the id it spells, or the text when it spells none. A column is written only
 * from an id, but the rows a device holds were also written by earlier builds and copied in by migrations, so a read
 * cannot promise one — each reader says what a row that holds none means (`docs/patterns.md`, "The data layer").
 */
sealed interface Stored<out T : Any> {
    /** The column spells an id. */
    data class Valid<out T : Any>(val value: T) : Stored<T>

    /** The column holds [text], which spells none. Written back as it was read. */
    data class Invalid(val text: String) : Stored<Nothing>
}

/** An id column: the id's text exactly as it arrived, and [parse]'s reading of it. */
abstract class StoredIdColumnAdapter<T : Any> : ColumnAdapter<Stored<T>, String> {
    protected abstract fun parse(text: String): T?

    protected abstract fun text(value: T): String

    override fun decode(databaseValue: String): Stored<T> =
        parse(databaseValue)?.let { Stored.Valid(it) } ?: Stored.Invalid(databaseValue)

    override fun encode(value: Stored<T>): String = when (value) {
        is Stored.Valid -> text(value.value)
        is Stored.Invalid -> value.text
    }
}

/** An asset id's column: its canonical string ([AssetId]). */
object AssetIdColumnAdapter : StoredIdColumnAdapter<AssetId>() {
    override fun parse(text: String) = AssetId.parse(text)

    override fun text(value: AssetId) = value.value
}

/** An event id's column: the id exactly as it arrived ([EventId]). */
object EventIdColumnAdapter : StoredIdColumnAdapter<EventId>() {
    override fun parse(text: String) = EventId.parse(text)

    override fun text(value: EventId) = value.value
}

/** A device id's column: the id exactly as it arrived ([DeviceId]). */
object DeviceIdColumnAdapter : StoredIdColumnAdapter<DeviceId>() {
    override fun parse(text: String) = DeviceId.parse(text)

    override fun text(value: DeviceId) = value.value
}

/** A date role's column: its timestamp's canonical text ([TimestampText]). Each role says how it wraps a timestamp. */
abstract class TimestampColumnAdapter<T : Any> : ColumnAdapter<T, String> {
    protected abstract fun wrap(at: Timestamp): T

    protected abstract fun unwrap(value: T): Timestamp

    override fun decode(databaseValue: String): T = wrap(TimestampText.parse(databaseValue))

    override fun encode(value: T): String = TimestampText.format(unwrap(value))
}

object CaptureCutoffColumnAdapter : TimestampColumnAdapter<CaptureCutoff>() {
    override fun wrap(at: Timestamp) = CaptureCutoff(at)

    override fun unwrap(value: CaptureCutoff) = value.at
}

object CaptureCeilingColumnAdapter : TimestampColumnAdapter<CaptureCeiling>() {
    override fun wrap(at: Timestamp) = CaptureCeiling(at)

    override fun unwrap(value: CaptureCeiling) = value.at
}

object EventStartColumnAdapter : TimestampColumnAdapter<EventStart>() {
    override fun wrap(at: Timestamp) = EventStart(at)

    override fun unwrap(value: EventStart) = value.at
}

object EventEndColumnAdapter : TimestampColumnAdapter<EventEnd>() {
    override fun wrap(at: Timestamp) = EventEnd(at)

    override fun unwrap(value: EventEnd) = value.at
}

object OpenUntilColumnAdapter : TimestampColumnAdapter<OpenUntil>() {
    override fun wrap(at: Timestamp) = OpenUntil(at)

    override fun unwrap(value: OpenUntil) = value.at
}

object DeletesAtColumnAdapter : TimestampColumnAdapter<DeletesAt>() {
    override fun wrap(at: Timestamp) = DeletesAt(at)

    override fun unwrap(value: DeletesAt) = value.at
}
