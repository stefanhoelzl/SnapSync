package app.snapsync.services.config

import app.snapsync.model.EventConfig
import app.snapsync.model.MemberCounts
import app.snapsync.services.ledger.db.MembershipQueries
import app.snapsync.services.ledger.db.SelectMembership

/** Writes [config] as the one membership row; each column's encoding is its adapter's (`sharedDatabaseOver`). */
fun MembershipQueries.saveMembership(config: EventConfig) = saveMembership(
    eventId = config.eventId,
    name = config.name,
    minPhotoDate = config.minPhotoDate,
    startsAt = config.startsAt,
    endsAt = config.endsAt,
    maxPhotoDate = config.maxPhotoDate,
    deletesAt = config.deletesAt,
    direction = config.direction,
    saveToAlbum = config.saveToAlbum,
    closed = config.closed,
    membersActive = config.members?.active?.toLong(),
    membersSettled = config.members?.settled?.toLong(),
    membersWaiting = config.members?.waiting?.toLong(),
    keyId = config.keyId,
    openUntil = config.openUntil,
    deleted = config.deleted,
)

/** The membership row as the config it stores. A value the domain refuses (a malformed instant or id) throws. */
fun SelectMembership.toConfig(): EventConfig = EventConfig(
    eventId = eventId,
    name = name,
    minPhotoDate = minPhotoDate,
    startsAt = startsAt,
    endsAt = endsAt,
    maxPhotoDate = maxPhotoDate,
    deletesAt = deletesAt,
    direction = direction,
    saveToAlbum = saveToAlbum,
    closed = closed,
    members = if (membersActive != null && membersSettled != null) {
        MemberCounts(membersActive.toInt(), membersSettled.toInt(), membersWaiting?.toInt())
    } else {
        null
    },
    keyId = keyId,
    openUntil = openUntil,
    deleted = deleted,
)
