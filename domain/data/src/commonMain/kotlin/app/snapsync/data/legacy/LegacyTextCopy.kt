package app.snapsync.data.legacy

import app.snapsync.data.store.LegacySource
import app.snapsync.data.store.Stored
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.PublishedDeclaration
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The file-held sources' copy: the manifest record and the pending leaves, each its rows and its mark in one go. */
internal class LegacyTextCopy(private val files: Files, private val homes: LegacyHomes) {
    private val log = Log("LegacyRecords")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `"<eventId> <manifest json>"`; one that does not parse is dropped, which costs one republish. The builds that
     * kept it in a file never declared a share settled.
     */
    fun copyManifest(db: LedgerDatabase) =
        copyFile(db, LegacySource.MANIFEST, LegacyHomes.LAST_UPLOADED) { text -> manifest(db, text) }

    /**
     * One leave per line: `"<eventId>"` or `"<eventId> received"` — whether it received everything is no longer kept;
     * a line naming no event is dropped.
     */
    fun copyLeaves(db: LedgerDatabase) =
        copyFile(db, LegacySource.PENDING_LEAVES, LegacyHomes.PENDING_LEAVES) { text -> leaves(db, text) }

    /** One file-held source: [rows] writes what the text holds, in the transaction that marks it. */
    private fun copyFile(db: LedgerDatabase, source: LegacySource, path: String, rows: (String) -> Unit) =
        when (val read = files.read(FileArea.SHARED, path)) {
            is FileResult.Ok -> copyText(db, Copied(source, path, read.value.decodeToString()), rows)
            FileResult.NotFound -> db.legacyImportQueries.markImported(source.key)
            FileResult.AreaUnavailable, is FileResult.Denied, is FileResult.Failed -> log.unlabelled.w(
                "legacy source unreadable — retried at the next open",
                "source" of source.key,
                "read" of read,
            )
        }

    private fun copyText(db: LedgerDatabase, copied: Copied, rows: (String) -> Unit) {
        db.transaction {
            rows(copied.text)
            db.legacyImportQueries.markImported(copied.source.key)
        }
        homes.delete(copied.path)
    }

    private fun manifest(db: LedgerDatabase, text: String) {
        val manifest = text.substringAfter(' ', "")
        val counts = (parsed(manifest) as? JsonObject)?.let(::countsOf)
            ?: return log.unlabelled.w("legacy manifest record does not parse — dropped")
        val eventId = EventId.parse(text.substringBefore(' '))
            ?: return log.unlabelled.w("legacy manifest record names no event — dropped")
        db.uploadedManifestQueries.recordUploaded(
            Stored.Valid(eventId),
            counts.version,
            manifest,
            PublishedDeclaration.SHARING,
            counts.assets,
        )
    }

    /** The manifest record's version and how many assets it declares, or `null` when it does not read as one. */
    private fun countsOf(record: JsonObject): ManifestCounts? {
        val version = (record["version"] as? JsonPrimitive)?.longOrNull
        val assets = record["assets"] as? JsonArray
        return version?.let { assets?.let { ManifestCounts(version, it.size.toLong()) } }
    }

    private fun parsed(text: String) = try {
        json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }

    private fun leaves(db: LedgerDatabase, text: String) {
        text.lines().map(String::trim).filter { it.isNotEmpty() }
            .mapNotNull { EventId.parse(it.substringBefore(' ')) }
            .forEach { db.pendingLeaveQueries.recordPendingLeave(Stored.Valid(it)) }
    }

    /** A file-held source's text, read from [path]. */
    private class Copied(val source: LegacySource, val path: String, val text: String)

    /** The manifest record's version and how many assets it declares. */
    private class ManifestCounts(val version: Long, val assets: Long)
}
