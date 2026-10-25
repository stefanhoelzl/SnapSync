package app.snapsync.data.membership

import app.snapsync.data.store.LegacySource
import app.snapsync.data.store.Stored
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.Membership

// What MembershipRepository runs inside the database, each a transaction's body. Every write after the entry is a
// compare-and-set — its UPDATE or DELETE names the event it was decided for — and `changes()` says whether it held.

/** The membership row, read only once the legacy file's membership has been copied in. */
internal fun LedgerDatabase.membershipRead(): MembershipRead =
    when (LegacySource.MEMBERSHIP.key in legacyImportQueries.selectImported().executeAsList()) {
        true -> membershipQueries.selectMembership().executeAsList().map(::stored).firstOrNull() ?: notJoined()
        false -> MembershipRead.Unavailable("the membership is still in its legacy file")
    }

/** Enter [active]'s membership, while none is held. */
internal fun LedgerDatabase.enter(active: Membership.Active): EnterResult = when (heldAgainst(active.event.id)) {
    Held.None -> EnterResult.Entered(active).also { membershipQueries.save(active) }
    Held.Same -> EnterResult.AlreadyJoined
    Held.Other -> EnterResult.JoinedElsewhere
}

private enum class Held { None, Same, Other }

private fun LedgerDatabase.heldAgainst(event: EventId): Held = membershipQueries.selectMembership().executeAsList()
    .map {
        when (it.eventId == Stored.Valid(event)) {
            true -> Held.Same
            false -> Held.Other
        }
    }
    .firstOrNull() ?: Held.None

/** Mark [event]'s membership, [active], deleted. */
internal fun LedgerDatabase.markDeleted(event: EventId, active: Membership.Active): MarkResult {
    membershipQueries.markDeleted(Stored.Valid(event))
    return when (changed()) {
        true -> MarkResult.Marked(Membership.Deleted(active.event, active.share))
        false -> MarkResult.Superseded
    }
}

/** Write [next]'s server facts into its membership. */
internal fun LedgerDatabase.update(next: Membership.Active): UpdateResult {
    with(next.event) {
        membershipQueries.updateEvent(
            name = name.text,
            membersActive = members?.active?.toLong(),
            membersSettled = members?.settled?.toLong(),
            membersWaiting = members?.waiting?.toLong(),
            openUntil = openUntil,
            eventId = Stored.Valid(id),
        )
    }
    return updated(next)
}

/** Write [next]'s share into its membership, and advance the manifest version with it. */
internal fun LedgerDatabase.reconfigure(next: Membership.Active): UpdateResult {
    membershipQueries.updateShare(
        minPhotoDate = next.share.range.from,
        maxPhotoDate = next.share.range.until,
        direction = next.share.direction,
        saveToAlbum = next.share.saveToAlbum,
        eventId = Stored.Valid(next.event.id),
    )
    return when (changed()) {
        true -> UpdateResult.Updated(next).also { ledgerQueries.bumpManifestVersion() }
        false -> UpdateResult.Superseded
    }
}

private fun LedgerDatabase.updated(next: Membership.Active): UpdateResult = when (changed()) {
    true -> UpdateResult.Updated(next)
    false -> UpdateResult.Superseded
}

private fun LedgerDatabase.changed(): Boolean = membershipQueries.changedRows().executeAsOne() > 0
