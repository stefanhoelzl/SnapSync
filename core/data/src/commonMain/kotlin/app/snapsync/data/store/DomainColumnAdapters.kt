package app.snapsync.data.store

import app.cash.sqldelight.ColumnAdapter
import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.album.AlbumFill
import app.snapsync.domain.album.EventAlbumId
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.time.Timestamp
import app.snapsync.format.TimestampText

// The domain's values as the text columns that hold them (`docs/patterns.md`, "Formats live at the boundary"): the one
// place each column's encoding lives, wired once per database.

/**
 * What an id column held, as it was read: the id it spells, or [Empty]. A column is written only from an id, but the
 * rows a device holds were also written by earlier builds and copied in by migrations, so a read cannot promise one —
 * each reader says what a row that holds none means (`docs/patterns.md`, "The data layer").
 *
 * The ONE text an earlier build left that spells no id is the empty string: ledger.db's `12.sqm` parked every row
 * under the empty event, and downloads.db's `6.sqm` gave the rows planned before it one (`PENDING_CLEANUPS.md`,
 * "Parked ledger rows" and "Download rows with an empty event"). Every id any build wrote was the backend's or the
 * platform's canonical id, so any other text is an [InvalidProgramState]: there is no reading of it to offer.
 */
sealed interface Stored<out T : Any> {
    /** The id the column spells, or `null` for [Empty]. */
    val value: T?

    /** The column spells an id. */
    data class Valid<out T : Any>(override val value: T) : Stored<T>

    /** The column holds the empty string a migration left: no id. Written back as it was read. */
    data object Empty : Stored<Nothing> {
        override val value: Nothing? get() = null
    }
}

/** An id column: the id's text exactly as it arrived, and [parse]'s reading of it. */
abstract class StoredIdColumnAdapter<T : Any> : ColumnAdapter<Stored<T>, String> {
    protected abstract fun parse(text: String): T?

    protected abstract fun text(value: T): String

    override fun decode(databaseValue: String): Stored<T> =
        parse(databaseValue)?.let { Stored.Valid(it) } ?: spellingNone(databaseValue)

    /** A column text that spells no id: only ever the empty one a migration left. */
    private fun spellingNone(text: String): Stored<T> =
        Stored.Empty.takeIf { text == EMPTY } ?: throw InvalidProgramState("an id column holds an id or no text")

    override fun encode(value: Stored<T>): String = when (value) {
        is Stored.Valid -> text(value.value)
        Stored.Empty -> EMPTY
    }

    private companion object {
        const val EMPTY = ""
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

/**
 * The membership's album column: 1 for an album, 0 for none — the bytes every build has written there, read as the
 * member's choice. Any other value reads as no album, as SQLDelight's own flag read it.
 */
object AlbumChoiceColumnAdapter : ColumnAdapter<AlbumChoice, Long> {
    override fun decode(databaseValue: Long): AlbumChoice = when (databaseValue == 1L) {
        true -> AlbumChoice.SaveToAlbum
        false -> AlbumChoice.LibraryOnly
    }

    override fun encode(value: AlbumChoice): Long = when (value) {
        AlbumChoice.SaveToAlbum -> 1L
        AlbumChoice.LibraryOnly -> 0L
    }
}

/** An event album's column: the id exactly as the platform wrote it. */
object EventAlbumIdColumnAdapter : ColumnAdapter<EventAlbumId, String> {
    override fun decode(databaseValue: String): EventAlbumId = EventAlbumId(databaseValue)

    override fun encode(value: EventAlbumId): String = value.value
}

/** An event album's mark: 1 once it has held a photo, 0 before — any other value reads as not yet filled. */
object AlbumFillColumnAdapter : ColumnAdapter<AlbumFill, Long> {
    override fun decode(databaseValue: Long): AlbumFill = when (databaseValue == 1L) {
        true -> AlbumFill.Filled
        false -> AlbumFill.NotYetFilled
    }

    override fun encode(value: AlbumFill): Long = when (value) {
        AlbumFill.Filled -> 1L
        AlbumFill.NotYetFilled -> 0L
    }
}
