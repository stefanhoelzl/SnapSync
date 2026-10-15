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
// place each column's encoding lives, wired once per database. A stored value that is not one of them throws as the
// row is read — as a malformed instant did when the row mapping parsed it — so a row is never read half-typed.

/**
 * An asset id's column: its canonical string — the form every row written before the type existed already holds, so
 * the adapter changes no byte on disk.
 */
object AssetIdColumnAdapter : ColumnAdapter<AssetId, String> {
    override fun decode(databaseValue: String): AssetId = AssetId(databaseValue)

    override fun encode(value: AssetId): String = value.value
}

/** An event id's column: the id exactly as it arrived ([EventId]). */
object EventIdColumnAdapter : ColumnAdapter<EventId, String> {
    override fun decode(databaseValue: String): EventId =
        requireNotNull(EventId.parse(databaseValue)) { "not an event id: '$databaseValue'" }

    override fun encode(value: EventId): String = value.value
}

/** A device id's column: the id exactly as it arrived ([DeviceId]). */
object DeviceIdColumnAdapter : ColumnAdapter<DeviceId, String> {
    override fun decode(databaseValue: String): DeviceId =
        requireNotNull(DeviceId.parse(databaseValue)) { "not a device id: '$databaseValue'" }

    override fun encode(value: DeviceId): String = value.value
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
