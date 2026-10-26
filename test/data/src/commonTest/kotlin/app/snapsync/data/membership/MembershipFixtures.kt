package app.snapsync.data.membership

import app.snapsync.data.store.LEDGER_DB_NAME
import app.snapsync.data.store.LegacyCopy
import app.snapsync.data.store.LegacySource
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventNameVerdict
import app.snapsync.domain.event.EventRange
import app.snapsync.domain.event.EventRangeVerdict
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.membership.Enrolled
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MintsEnrolled
import app.snapsync.domain.membership.Share
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import app.snapsync.format.TimestampText
import app.snapsync.mock.DatabasesMock
import app.snapsync.testids.testEventId
import kotlin.test.assertIs

internal const val EVENT = "0f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f60"
internal const val OTHER = "1f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f61"

/** Every legacy record already copied: the database is the records' one home. */
internal object Copied : LegacyCopy {
    override fun copyInto(
        db: LedgerDatabase,
    ) = LegacySource.entries.forEach { db.legacyImportQueries.markImported(it.key) }
}

/** The shared database of [device], its legacy records copied. */
internal fun sharedOver(device: DatabasesMock): SharedDatabase = SharedDatabase(device.port(), Copied)

internal fun name(text: String): EventName = assertIs<EventNameVerdict.Ok>(EventName.of(text)).name

/** An event from Tuesday 18:00 to the next Tuesday 18:00. */
internal fun event(id: String = EVENT, name: String = "Lake weekend"): Event = Event(
    id = testEventId(id),
    name = name(name),
    range = assertIs<EventRangeVerdict.Ok>(
        EventRange.of(
            EventStart(TimestampText.parse("2026-07-14T18:00:00Z")),
            EventEnd(TimestampText.parse("2026-07-21T18:00:00Z")),
        ),
    ).range,
    openUntil = null,
    deletesAt = DeletesAt(TimestampText.parse("2026-08-21T18:00:00Z")),
    encryption = Encryption.Plain,
    members = null,
)

/** The event's own dates shared both ways — or [from]..[until] of it. */
internal fun share(
    of: Event = event(),
    direction: Direction = Direction.Both,
    from: String = "2026-07-14T18:00:00Z",
    until: String = "2026-07-21T18:00:00Z",
): Share = Share(
    assertIs<CaptureRangeVerdict.Ok>(
        CaptureRange.of(
            CaptureCutoff(TimestampText.parse(from)),
            CaptureCeiling(TimestampText.parse(until)),
            of.range.start,
            of.range.end,
        ),
    ).range,
    direction,
    saveToAlbum = false,
)

internal fun active(id: String = EVENT): Membership.Active = event(id).let { Membership.Active(it, share(it)) }

@OptIn(MintsEnrolled::class)
internal fun enrolled(id: String = EVENT): Enrolled = Enrolled(active(id))

@OptIn(MintsEnrolled::class)
internal fun enrolled(membership: Membership.Active): Enrolled = Enrolled(membership)

/** The `ledger.db` [device] holds. */
internal val LEDGER = LEDGER_DB_NAME
