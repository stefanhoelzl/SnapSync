package app.snapsync.domain.event

import app.snapsync.domain.time.Timestamp
import kotlin.test.assertIs
import kotlin.time.Instant

/** [text] as a timestamp. */
internal fun ts(text: String): Timestamp = Timestamp.of(Instant.parse(text))

/** A name that is one. */
internal fun name(text: String): EventName = assertIs<EventNameVerdict.Ok>(EventName.of(text)).name

/** An event from Tuesday 18:00 to the next Tuesday 18:00, deleted at the latest a month later. */
internal fun anEvent(): Event = Event(
    id = EventId.parse("0f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f60")!!,
    name = name("Lake weekend"),
    range = assertIs<EventRangeVerdict.Ok>(
        EventRange.of(EventStart(ts("2026-07-14T18:00:00Z")), EventEnd(ts("2026-07-21T18:00:00Z"))),
    ).range,
    openUntil = null,
    deletesAt = DeletesAt(ts("2026-08-21T18:00:00Z")),
    encryption = Encryption.Plain,
    members = null,
)
