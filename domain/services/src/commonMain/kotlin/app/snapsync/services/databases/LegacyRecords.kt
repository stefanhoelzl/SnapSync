package app.snapsync.services.databases

import app.snapsync.domain.event.EventId
import app.snapsync.model.ConfigFileDecode
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.PrefRead
import app.snapsync.model.decodeConfigFile
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Files
import app.snapsync.ports.Preferences
import app.snapsync.services.config.saveMembership
import app.snapsync.services.ledger.db.LedgerDatabase
import app.snapsync.services.wake.EventCheck
import co.touchlab.kermit.Logger
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The membership's legacy file in the shared area's root — runtime identity: an updated device still holds it. */
const val CONFIG_FILE_NAME: String = "eventconfig.json"

/** The manifest record's legacy directory in the shared area — runtime identity. */
private const val DEVICE_MANIFEST_DIR = "device-manifest"

/** The manifest record's legacy file name — runtime identity. */
private const val LAST_UPLOADED_FILE = "last-uploaded.json"

private const val LAST_UPLOADED = "$DEVICE_MANIFEST_DIR/$LAST_UPLOADED_FILE"

/** The pending leaves' legacy file — runtime identity. */
private const val PENDING_LEAVES = "membership/pending-leaves.txt"

/** The event-album map's legacy Preferences key — runtime identity. */
private const val ALBUM_MAP_KEY = "app.snapsync.album.map"

/** The event-album filled marks' legacy Preferences key — runtime identity. */
private const val ALBUM_FILLED_KEY = "app.snapsync.album.filled"

/** A pending leave's legacy marker of "received every photo"; a line without it did not. */
private const val RECEIVED = "received"

/** One record's legacy home, as `legacyImport` names it once its rows are in the shared database. */
enum class LegacySource(val key: String) {
    MEMBERSHIP("membership"),
    MANIFEST("manifest"),
    PENDING_LEAVES("pendingLeaves"),
    ALBUM("album"),
    CHECKS("checks"),
}

/**
 * **The app's one-time copy of the shared records' legacy homes into `ledger.db`** — a
 * shim, listed in `PENDING_CLEANUPS.md`. Before the records moved into the database, they lived in files in the shared
 * area and in Preferences keys; an updated device still holds them there.
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
 * The membership's copy carries the ledger rows 12.sqm parked under the empty event: adopted into the copied event, or
 * dropped when there is none. Those rows belong to the event joined when 12.sqm ran, so no later join may adopt them.
 */
class LegacyRecords(
    private val files: Files,
    private val preferences: Preferences,
    private val log: Logger = Logger.withTag("legacyRecords"),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Copies every source [db] has not marked yet, and deletes what a marked one left behind. Never raises: a source
     * that fails stays pending, and a leftover that is not deleted now is deleted at a later open.
     */
    fun copyInto(db: LedgerDatabase) {
        val done = db.legacyImportQueries.selectImported().executeAsList().toSet()
        LegacySource.entries.forEach { source ->
            runCatchingCancellable { if (source.key in done) forgetLeftover(source, db) else copy(source, db) }
                .onFailure { log.w(it) { "legacy ${source.key} not copied — retried at the next open" } }
        }
    }

    /** A copied source's legacy home, deleted if a crash left it behind: its mark already outranks it. */
    private fun forgetLeftover(source: LegacySource, db: LedgerDatabase) {
        when (source) {
            LegacySource.MEMBERSHIP -> delete(CONFIG_FILE_NAME)
            LegacySource.MANIFEST -> delete(LAST_UPLOADED)
            LegacySource.PENDING_LEAVES -> delete(PENDING_LEAVES)
            LegacySource.ALBUM -> listOf(ALBUM_MAP_KEY, ALBUM_FILLED_KEY).forEach(preferences::remove)
            LegacySource.CHECKS -> joinedEvent(db)?.let { eventId ->
                EventCheck.entries.forEach { preferences.remove(checkKey(it, eventId)) }
            }
        }
    }

    private fun copy(source: LegacySource, db: LedgerDatabase) = when (source) {
        LegacySource.MEMBERSHIP -> copyMembership(db)
        LegacySource.MANIFEST -> copyFile(db, source, LAST_UPLOADED) { text -> copyManifest(db, text) }
        LegacySource.PENDING_LEAVES -> copyFile(db, source, PENDING_LEAVES) { text -> copyLeaves(db, text) }
        LegacySource.ALBUM -> copyAlbum(db)
        LegacySource.CHECKS -> copyChecks(db)
    }

    private fun copyMembership(db: LedgerDatabase) {
        when (val read = files.read(FileArea.SHARED, CONFIG_FILE_NAME)) {
            is FileResult.Ok -> {
                val decoded = decodeConfigFile(read.value.decodeToString(throwOnInvalidSequence = true))
                if (decoded !is ConfigFileDecode.Valid) {
                    return log.w { "the legacy membership does not decode ($decoded) — left in place, retried" }
                }
                db.transaction {
                    db.membershipQueries.saveMembership(decoded.config)
                    db.ledgerQueries.adoptParked()
                    db.legacyImportQueries.markImported(LegacySource.MEMBERSHIP.key)
                }
                delete(CONFIG_FILE_NAME)
                log.i { "legacy membership of ${decoded.config.eventId} copied" }
            }
            FileResult.NotFound -> db.transaction {
                db.ledgerQueries.dropParked()
                db.legacyImportQueries.markImported(LegacySource.MEMBERSHIP.key)
            }
            else -> log.w { "the legacy membership is unreadable ($read) — retried at the next open" }
        }
    }

    /** One file-held source: [rows] writes what the text holds, in the transaction that marks it. */
    private fun copyFile(db: LedgerDatabase, source: LegacySource, path: String, rows: (String) -> Unit) {
        when (val read = files.read(FileArea.SHARED, path)) {
            is FileResult.Ok -> {
                val text = read.value.decodeToString()
                db.transaction {
                    rows(text)
                    db.legacyImportQueries.markImported(source.key)
                }
                delete(path)
            }
            FileResult.NotFound -> db.legacyImportQueries.markImported(source.key)
            else -> log.w { "the legacy ${source.key} is unreadable ($read) — retried at the next open" }
        }
    }

    /** `"<eventId> <manifest json>"`; one that does not parse is dropped, which costs one republish. */
    private fun copyManifest(db: LedgerDatabase, text: String) {
        val manifest = text.substringAfter(' ', "")
        val version = runCatchingCancellable {
            json.parseToJsonElement(manifest).jsonObject.getValue("version").jsonPrimitive.long
        }.getOrElse { return log.w { "the legacy manifest record does not parse — dropped" } }
        val eventId = EventId.parse(text.substringBefore(' '))
            ?: return log.w { "the legacy manifest record names no event — dropped" }
        db.uploadedManifestQueries.recordUploaded(eventId, version, manifest)
    }

    /** One leave per line: `"<eventId>"` or `"<eventId> received"`; a line naming no event is dropped. */
    private fun copyLeaves(db: LedgerDatabase, text: String) {
        text.lines().map(String::trim).filter { it.isNotEmpty() }.forEach { line ->
            val eventId = EventId.parse(line.substringBefore(' ')) ?: return@forEach
            db.pendingLeaveQueries.recordPendingLeave(eventId, line.substringAfter(' ', "") == RECEIVED)
        }
    }

    private fun copyAlbum(db: LedgerDatabase) {
        val map = preferences.get(ALBUM_MAP_KEY)
        val filled = preferences.get(ALBUM_FILLED_KEY)
        if (listOf(map, filled).any { it is PrefRead.Unavailable }) {
            return log.w { "the legacy event-album map is unreadable — retried at the next open" }
        }
        val albums = (map as? PrefRead.Value)?.let {
            runCatchingCancellable {
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it.value)
            }.getOrDefault(emptyMap())
        }.orEmpty()
        val filledEvents = (filled as? PrefRead.Value)?.let {
            runCatchingCancellable { json.decodeFromString(SetSerializer(String.serializer()), it.value) }
                .getOrDefault(emptySet())
        }.orEmpty()
        db.transaction {
            // An entry naming no event is dropped: its album is found again by name at the next placement.
            albums.forEach { (key, album) ->
                val eventId = EventId.parse(key) ?: return@forEach
                db.eventAlbumQueries.importAlbum(eventId, album, key in filledEvents)
            }
            db.legacyImportQueries.markImported(LegacySource.ALBUM.key)
        }
        preferences.remove(ALBUM_MAP_KEY)
        preferences.remove(ALBUM_FILLED_KEY)
    }

    /**
     * The joined event's check times. Keys were per event, and the Preferences port cannot list keys, so only the
     * membership's event is copied — a leave always forgot its event's times, so any other event's are leftovers. A
     * time not copied only makes its next background check due at once: one extra request.
     */
    private fun copyChecks(db: LedgerDatabase) {
        val eventId = joinedEvent(db)
        val times = eventId?.let { joined ->
            EventCheck.entries.associateWith { preferences.get(checkKey(it, joined)) }
        }.orEmpty()
        if (times.values.any { it is PrefRead.Unavailable }) {
            return log.w { "the legacy check times are unreadable — retried at the next open" }
        }
        db.transaction {
            times.forEach { (check, read) ->
                (read as? PrefRead.Value)?.let { it.value.toLongOrNull() }?.let {
                    db.eventCheckQueries.stampCheck(eventId!!, check.key, it)
                }
            }
            db.legacyImportQueries.markImported(LegacySource.CHECKS.key)
        }
        times.keys.forEach { preferences.remove(checkKey(it, eventId!!)) }
    }

    private fun delete(path: String) {
        val deleted = files.delete(FileArea.SHARED, path)
        if (deleted !is FileResult.Ok && deleted != FileResult.NotFound) {
            log.w { "legacy $path copied but not deleted ($deleted) — its mark outranks it; deleted at a later open" }
        }
    }

    private fun joinedEvent(db: LedgerDatabase): EventId? =
        db.membershipQueries.selectMembership().executeAsOneOrNull()?.eventId

    private fun checkKey(check: EventCheck, eventId: EventId) = "app.snapsync.check.${check.key}.$eventId"
}
