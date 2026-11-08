package app.snapsync.data.legacy

import app.snapsync.data.store.LegacyCopy
import app.snapsync.data.store.LegacySource
import app.snapsync.data.store.OwnedDatabase
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.DbResult
import app.snapsync.ports.Files
import app.snapsync.ports.Preferences

/**
 * **The app's one-time copy of the shared records' legacy homes into `ledger.db`** — a shim, listed in
 * `PENDING_CLEANUPS.md`. Before the records moved into the database, they lived in files in the shared area and in
 * Preferences keys; an updated device still holds them there.
 *
 * Run by [SharedDatabase] once per process, right after the app's open; the extension never runs it and pauses until
 * it has run. Each source not yet in `legacyImport` is decided by what its legacy read answers:
 * - **contents** → its rows and its mark in ONE transaction, then the file or key is deleted. A crash before the delete
 *   leaves a leftover the mark outranks: it is never read again, and the next open deletes it.
 * - **absent** → its mark alone: nothing to copy (a fresh install, or nothing was ever recorded).
 * - **unreadable or undecodable** → nothing: the source stays pending and is retried at the next open, and its reader
 *   answers "could not look" meanwhile — never "absent". This is what keeps an unreadable membership from ever reading
 *   as a leave.
 *
 * Each source runs as its own run of statements: one that fails stays pending, and the others are copied.
 */
class LegacyRecords(files: Files, preferences: Preferences) : LegacyCopy {
    private val log = Log("LegacyRecords")
    private val homes = LegacyHomes(files, preferences)
    private val membership = LegacyMembershipCopy(files, homes)
    private val texts = LegacyTextCopy(files, homes)
    private val albums = LegacyAlbumCopy(preferences, homes)
    private val checks = LegacyChecksCopy(preferences)

    /**
     * Copies every source [db] has not marked yet, and deletes what a marked one left behind. A source that fails stays
     * pending, and a leftover that is not deleted now is deleted at a later open; only a database that cannot say which
     * sources it holds fails the open.
     */
    override fun copyInto(db: OwnedDatabase.Opened<LedgerDatabase>): DbResult<Unit> =
        when (val done = db.execute { it.legacyImportQueries.selectImported().executeAsList().toSet() }) {
            is DbResult.Ok -> DbResult.Ok(LegacySource.entries.forEach { copyAlone(it, done.value, db) })
            is DbResult.Failed -> done
        }

    /** [source] as its own run of statements, failing alone. */
    private fun copyAlone(source: LegacySource, done: Set<String>, db: OwnedDatabase.Opened<LedgerDatabase>) =
        copied(source, db.execute<Unit> { one(source, done, it) })

    private fun one(source: LegacySource, done: Set<String>, db: LedgerDatabase) = when (source.key in done) {
        true -> forgetLeftover(source, db)
        false -> copy(source, db)
    }

    private fun copied(source: LegacySource, copy: DbResult<Unit>) = when (copy) {
        is DbResult.Ok -> Unit
        is DbResult.Failed -> log.unlabelled.w(
            "legacy source not copied — retried at the next open",
            "source" of source.key,
            "detail" of copy.detail,
        )
    }

    /** A copied source's legacy home, deleted if a crash left it behind: its mark already outranks it. */
    private fun forgetLeftover(source: LegacySource, db: LedgerDatabase) = when (source) {
        LegacySource.MEMBERSHIP -> homes.delete(LegacyHomes.CONFIG_FILE_NAME)
        LegacySource.MANIFEST -> homes.delete(LegacyHomes.LAST_UPLOADED)
        LegacySource.PENDING_LEAVES -> homes.delete(LegacyHomes.PENDING_LEAVES)
        LegacySource.ALBUM -> homes.forgetAlbums()
        LegacySource.CHECKS -> checks.forget(db)
    }

    private fun copy(source: LegacySource, db: LedgerDatabase) = when (source) {
        LegacySource.MEMBERSHIP -> membership.copy(db)
        LegacySource.MANIFEST -> texts.copyManifest(db)
        LegacySource.PENDING_LEAVES -> texts.copyLeaves(db)
        LegacySource.ALBUM -> albums.copy(db)
        LegacySource.CHECKS -> checks.copy(db)
    }

    companion object {
        /** The membership's legacy file in the shared area's root — runtime identity: an updated device holds it. */
        const val CONFIG_FILE_NAME: String = LegacyHomes.CONFIG_FILE_NAME
    }
}
