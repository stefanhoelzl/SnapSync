package app.snapsync.services.config

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.DeletesAt
import app.snapsync.model.EventConfig
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.MemberCounts
import app.snapsync.services.ledger.db.MembershipQueries
import app.snapsync.services.ledger.db.SelectMembership

/** Writes [config] as the one membership row — the one place that knows each column's encoding. */
fun MembershipQueries.saveMembership(config: EventConfig) = saveMembership(
    eventId = config.eventId,
    name = config.name,
    minPhotoDate = config.minPhotoDate.at.iso,
    startsAt = config.startsAt.at.iso,
    endsAt = config.endsAt.at.iso,
    maxPhotoDate = config.maxPhotoDate.at.iso,
    deletesAt = config.deletesAt.at.iso,
    direction = config.direction,
    saveToAlbum = config.saveToAlbum,
    closed = config.closed,
    membersActive = config.members?.active?.toLong(),
    membersSettled = config.members?.settled?.toLong(),
    keyId = config.keyId,
)

/** The membership row as the config it stores. A value the model refuses (a malformed instant) throws. */
fun SelectMembership.toConfig(): EventConfig = EventConfig(
    eventId = eventId,
    name = name,
    minPhotoDate = CaptureCutoff(CaptureDate.parse(minPhotoDate)),
    startsAt = EventStart(CaptureDate.parse(startsAt)),
    endsAt = EventEnd(CaptureDate.parse(endsAt)),
    maxPhotoDate = CaptureCeiling(CaptureDate.parse(maxPhotoDate)),
    deletesAt = DeletesAt(CaptureDate.parse(deletesAt)),
    direction = direction,
    saveToAlbum = saveToAlbum,
    closed = closed,
    members = if (membersActive != null && membersSettled != null) {
        MemberCounts(membersActive.toInt(), membersSettled.toInt())
    } else {
        null
    },
    keyId = keyId,
)
