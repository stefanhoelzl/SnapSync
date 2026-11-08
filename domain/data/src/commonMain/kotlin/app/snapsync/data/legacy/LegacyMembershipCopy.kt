package app.snapsync.data.legacy

import app.snapsync.data.store.LegacySource
import app.snapsync.data.store.Stored
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.domain.album.AlbumChoice
import app.snapsync.format.LegacyMembership
import app.snapsync.format.LegacyMembershipDecode
import app.snapsync.format.LegacyMembershipText
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files

/**
 * The membership's copy: `eventconfig.json` into the one membership row. It carries the ledger rows 12.sqm parked under
 * the empty event: adopted into the copied event, or dropped when there is none. Those rows belong to the event joined
 * when 12.sqm ran, so no later join may adopt them. A file that does not read is left in place — never a leave.
 */
internal class LegacyMembershipCopy(private val files: Files, private val homes: LegacyHomes) {
    private val log = Log("LegacyRecords")

    fun copy(db: LedgerDatabase) = copy(db, files.read(FileArea.SHARED, LegacyHomes.CONFIG_FILE_NAME))

    private fun copy(db: LedgerDatabase, read: FileResult<ByteArray>) = when (read) {
        is FileResult.Ok -> copy(db, LegacyMembershipText.decode(read.value))
        FileResult.NotFound -> db.transaction {
            db.ledgerQueries.dropParked()
            db.legacyImportQueries.markImported(LegacySource.MEMBERSHIP.key)
        }
        FileResult.AreaUnavailable, is FileResult.Denied, is FileResult.Failed ->
            log.unlabelled.w("legacy membership unreadable — retried at the next open", "read" of read)
    }

    private fun copy(db: LedgerDatabase, decoded: LegacyMembershipDecode) = when (decoded) {
        is LegacyMembershipDecode.Valid -> copy(db, decoded.membership)
        LegacyMembershipDecode.Unusable, is LegacyMembershipDecode.Foreign ->
            log.unlabelled.w("legacy membership does not decode — left in place, retried", "decoded" of decoded)
    }

    private fun copy(db: LedgerDatabase, membership: LegacyMembership) {
        db.transaction {
            save(db, membership)
            db.ledgerQueries.adoptParked()
            db.legacyImportQueries.markImported(LegacySource.MEMBERSHIP.key)
        }
        homes.delete(LegacyHomes.CONFIG_FILE_NAME)
        log.unlabelled.i("legacy membership copied")
    }

    /** [membership], a legacy file's membership, as the one membership row. */
    private fun save(db: LedgerDatabase, membership: LegacyMembership) = db.membershipQueries.saveMembership(
        eventId = Stored.Valid(membership.eventId),
        name = membership.name,
        minPhotoDate = membership.minPhotoDate,
        startsAt = membership.startsAt,
        endsAt = membership.endsAt,
        maxPhotoDate = membership.maxPhotoDate,
        deletesAt = membership.deletesAt,
        direction = membership.direction,
        saveToAlbum = albumChoiceOf(membership.saveToAlbum),
        membersActive = membership.members?.active?.toLong(),
        membersSettled = membership.members?.settled?.toLong(),
        membersWaiting = membership.members?.waiting?.toLong(),
        keyId = membership.keyId,
        openUntil = membership.openUntil,
        deleted = membership.deleted,
    )

    private fun albumChoiceOf(saveToAlbum: Boolean) = when (saveToAlbum) {
        true -> AlbumChoice.SaveToAlbum
        false -> AlbumChoice.LibraryOnly
    }
}
