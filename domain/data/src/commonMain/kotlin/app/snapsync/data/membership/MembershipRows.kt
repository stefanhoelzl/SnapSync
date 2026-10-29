package app.snapsync.data.membership

import app.snapsync.data.Parsed
import app.snapsync.data.eventName
import app.snapsync.data.parsed
import app.snapsync.data.store.Stored
import app.snapsync.data.store.db.MembershipQueries
import app.snapsync.data.store.db.SelectMembership
import app.snapsync.data.then
import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventRange
import app.snapsync.domain.event.EventRangeVerdict
import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MintsConfirmedNotJoined
import app.snapsync.domain.membership.Share
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.format.EncryptionText

/** No membership: the store answered with none. */
@OptIn(MintsConfirmedNotJoined::class)
internal fun notJoined(): MembershipRead = MembershipRead.NotJoined(ConfirmedNotJoined())

/** The membership [row] stores, or why it cannot be read. */
internal fun stored(row: SelectMembership): MembershipRead = when (val membership = row.membership()) {
    is Parsed.Ok -> MembershipRead.Joined(membership.value)
    is Parsed.Refused ->
        MembershipRead.Unavailable("the stored membership is unreadable: ${membership.detail}", refused = true)
}

// The membership row as the domain reads it, and back. A row is the store's: a value the domain refuses in it — a
// blank name, an inverted range, a share outside its event — makes the membership unreadable, never guessed around.

/** The membership [this] row stores. */
internal fun SelectMembership.membership(): Parsed<Membership> =
    event().then { event -> share(event.range).then { share -> Parsed.Ok(typed(event, share)) } }

private fun SelectMembership.typed(event: Event, share: Share): Membership = when (deleted) {
    true -> Membership.Deleted(event, share)
    false -> Membership.Active(event, share)
}

private fun SelectMembership.event(): Parsed<Event> = parsed(eventId, "event id").then { id ->
    eventName(name).then { name -> range().then { range -> Parsed.Ok(event(id, name, range)) } }
}

private fun SelectMembership.event(id: EventId, name: EventName, range: EventRange): Event =
    Event(id, name, range, openUntil, deletesAt, EncryptionText.parse(keyId), counts())

private fun SelectMembership.range(): Parsed<EventRange> = when (val range = EventRange.of(startsAt, endsAt)) {
    is EventRangeVerdict.Ok -> Parsed.Ok(range.range)
    EventRangeVerdict.Inverted -> Parsed.Refused("its event ends before it starts")
}

private fun SelectMembership.share(event: EventRange): Parsed<Share> =
    when (val range = CaptureRange.of(minPhotoDate, maxPhotoDate, event.start, event.end)) {
        is CaptureRangeVerdict.Ok -> Parsed.Ok(Share(range.range, direction, saveToAlbum))
        CaptureRangeVerdict.Inverted, CaptureRangeVerdict.TooLong, CaptureRangeVerdict.OutsideEvent ->
            Parsed.Refused("its shared range is $range")
    }

// The schema stores both counts or neither.
private fun SelectMembership.counts(): MemberCounts? = listOfNotNull(membersActive, membersSettled)
    .takeIf { it.size == 2 }
    ?.let { (active, settled) -> MemberCounts(active.toInt(), settled.toInt(), membersWaiting?.toInt()) }

/** [membership] as the one membership row. */
internal fun MembershipQueries.save(membership: Membership.Active) = with(membership) {
    saveMembership(
        eventId = Stored.Valid(event.id),
        name = event.name.text,
        minPhotoDate = share.range.from,
        startsAt = event.range.start,
        endsAt = event.range.end,
        maxPhotoDate = share.range.until,
        deletesAt = event.deletesAt,
        direction = share.direction,
        saveToAlbum = share.saveToAlbum,
        membersActive = event.members?.active?.toLong(),
        membersSettled = event.members?.settled?.toLong(),
        membersWaiting = event.members?.waiting?.toLong(),
        keyId = EncryptionText.of(event.encryption),
        openUntil = event.openUntil,
        deleted = false,
    )
}
