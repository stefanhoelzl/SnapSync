package app.snapsync.rig

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.data.store.LEDGER_DB_NAME
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.domain.time.Timestamp
import app.snapsync.format.TimestampText
import app.snapsync.levers.SeededInstall
import app.snapsync.mock.DownloadSessionMock
import app.snapsync.model.PrefRead
import app.snapsync.ports.DbOpen
import app.snapsync.ports.DbResult
import app.snapsync.ports.FileArea
import app.snapsync.services.downloads.DOWNLOADS_DB_NAME
import app.snapsync.services.downloads.db.DownloadDatabase
import app.snapsync.wire.api.Limits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// **What an older install left** (`OlderInstallLevers.installedAt`): what the device holds now, rewritten as a device
// whose databases are older would hold it — the moment before an update.
//
// A database at an older version is BUILT, never written out: this build's own migrations run from the oldest version
// the chain still builds, and as each version is passed the rows that version's build wrote are added — its LAYER.
// So the chain is the only source of every older shape (`deriveSchemaFromMigrations`), and a migration added later adds
// a case by itself; only a migration that brings a new kind of record adds a layer. What lived outside a database at a
// version — the shared records' legacy homes, a transfer an earlier build started — is a layer too, written in the
// format that build wrote and deleted by the layer of the build that moved it.
//
// Every format cites the source it copies, `<tag>:<path>:<line>`. The tags are the releases that shipped each version:
// ledger.db v12 and downloads.db v5 are 0.4's, v13 and v7 0.5's, v15 and v9 0.6's.
//
// Each layer is deleted with the shim that reads it (`PENDING_CLEANUPS.md`), and squashing a chain folds the layers
// below its new baseline into the new baseline's.

/** The device as an install with older databases left it: [ledgerBehind] and [downloadsBehind] versions back. */
internal class OlderInstalls(private val world: MockWorld) {
    private val databases = world.device.databases
    private val disk = world.device.disk
    private val preferences = world.device.preferences.port()

    fun installedAt(ledgerBehind: Int?, downloadsBehind: Int?): SeededInstall {
        val ledger = LedgerDatabase.Schema.version - (ledgerBehind ?: 0)
        val downloads = DownloadDatabase.Schema.version - (downloadsBehind ?: 0)
        require(ledger >= LEDGER_BASELINE) { "the chain builds ledger.db from v$LEDGER_BASELINE, not v$ledger" }
        require(
            downloads >= DOWNLOADS_BASELINE,
        ) { "the chain builds downloads.db from v$DOWNLOADS_BASELINE, not v$downloads" }
        require(
            ledgerBehind == null || ledgerBehind > 0,
        ) { "ledgerBehind counts versions back from this build's: at least 1" }
        require(downloadsBehind == null || downloadsBehind > 0) {
            "downloadsBehind counts versions back from this build's: at least 1"
        }

        val now = Held(held(LEDGER_DB_NAME, LedgerDatabase.Schema), held(DOWNLOADS_DB_NAME, DownloadDatabase.Schema))
        val uploads = mutableListOf<String>()
        val downloadTags = mutableListOf<String>()
        if (ledgerBehind != null) {
            rebuild(LEDGER_DB_NAME, LedgerDatabase.Schema, LEDGER_BASELINE, ledger) { version, db ->
                ledgerLayer(version, db, now, uploads)
            }
        }
        if (downloadsBehind != null) {
            rebuild(DOWNLOADS_DB_NAME, DownloadDatabase.Schema, DOWNLOADS_BASELINE, downloads) { version, db ->
                downloadsLayer(version, db, now, downloadTags)
            }
        }
        return SeededInstall(
            ledger = ledger.toInt(),
            olderLedgers = (ledger - LEDGER_BASELINE).toInt(),
            downloads = downloads.toInt(),
            olderDownloads = (downloads - DOWNLOADS_BASELINE).toInt(),
            legacyRecords = ledger < RECORDS_MOVED,
            uploadsWithNoEvent = uploads,
            downloadsWithNoEvent = downloadTags,
        )
    }

    /**
     * The joined membership's share range stretched past what this build's rules allow ([REFUSED_RANGE] from its start),
     * on the device's own ledger — as a build with looser rules could have stored it.
     */
    fun refusedShareRange() {
        val opened = databases.inspectorPort().open(LEDGER_DB_NAME, LedgerDatabase.Schema, readOnly = false)
        val database = (opened as? DbOpen.Opened)?.database ?: error("ledger.db is not writable as this build's ($opened)")
        val written = database.execute { driver ->
            val db = Tables(driver)
            val held = db.snapshot().of("membership").singleOrNull() ?: error("no membership is held to refuse")
            val from = Instant.parse(held.getValue("minPhotoDate") ?: error("the membership has no range"))
            db.write(
                "UPDATE membership SET maxPhotoDate = ? WHERE id = 0",
                TimestampText.format(Timestamp.of(from + REFUSED_RANGE)),
            )
        }
        check(written is DbResult.Ok) { "the membership could not be rewritten: $written" }
    }

    // ---- ledger.db ----------------------------------------------------------------------------------------------

    /** What the build at ledger.db [version] wrote on top of what it found — applied as the build passes [version]. */
    private fun ledgerLayer(version: Long, db: Tables, now: Held, uploads: MutableList<String>) {
        val joined = now.joined
        when (version) {
            // 0.4's: every row with no event — the joined one lived in eventconfig.json — and a destination on the
            // byte route that named none; the shared records in their legacy homes; its own uploads still in flight.
            LEDGER_BASELINE -> {
                now.ledger.of("ledgerRow").forEach { row ->
                    db.insert(
                        "ledgerRow",
                        row - "eventId" + ("destinationPath" to row["destinationPath"]?.let(::withNoEvent)),
                    )
                }
                now.ledger.of("manifestVersion").singleOrNull()?.let {
                    db.write("INSERT OR REPLACE INTO manifestVersion (id, value) VALUES (0, ?)", it["value"])
                }
                writeLegacyRecords(now)
                uploads += startedByZeroFour()
            }
            // 0.5's: 12.sqm parked every row under the empty event, and its first use adopted them into the joined one
            // (v0.5:domain/services/src/commonMain/kotlin/app/snapsync/services/ledger/LedgerService.kt:63,
            // v0.5:domain/services/src/commonMain/sqldelight/ledger/app/snapsync/services/ledger/db/Ledger.sq:210).
            LEDGER_BASELINE + 1 -> if (joined != null) {
                db.write("UPDATE OR IGNORE ledgerRow SET eventId = ? WHERE eventId = ''", joined)
            }
            // 0.6's: 13.sqm made the records' tables, and the app's first open copied each legacy home into them, marked
            // it, and deleted it (v0.6:domain/services/src/commonMain/kotlin/app/snapsync/services/databases/LegacyRecords.kt).
            RECORDS_MOVED -> {
                now.ledger.of("membership").forEach { db.insert("membership", it - OPEN_UNTIL_FACTS) }
                // A leave copied from 0.4's line carries no marker, so it says it had not received everything — the
                // `received` column 16.sqm dropped (v0.6:domain/services/src/commonMain/kotlin/app/snapsync/services/databases/LegacyRecords.kt:43,161).
                now.ledger.of("pendingLeave").forEach { db.insert("pendingLeave", mapOf("received" to "0") + it) }
                listOf(
                    "uploadedManifest",
                    "eventAlbum",
                    "eventCheck",
                    "legacyImport",
                ).forEach { table ->
                    now.ledger.of(table).forEach { db.insert(table, it) }
                }
                forgetLegacyRecords(now)
            }
            // 0.6's too: 14.sqm added the open-until facts, which the event's details then carried.
            RECORDS_MOVED + 1 -> now.ledger.of("membership").singleOrNull()?.let { row ->
                db.write(
                    "UPDATE membership SET openUntil = ?, membersWaiting = ?, deleted = ?",
                    row["openUntil"],
                    row["membersWaiting"],
                    row["deleted"],
                )
            }
            else -> Unit
        }
    }

    /**
     * The shared records where 0.4 kept them — files in the shared area and Preferences keys — and the push record's
     * file, which moved with them.
     */
    private fun writeLegacyRecords(now: Held) {
        val membership = now.ledger.of("membership").singleOrNull()
        // v0.4:domain/services/src/commonMain/kotlin/app/snapsync/services/config/ConfigService.kt:26,81 — the envelope of
        // v0.4:domain/model/src/commonMain/kotlin/app/snapsync/model/ConfigFile.kt:64 over the EventConfig of
        // v0.4:domain/model/src/commonMain/kotlin/app/snapsync/model/EventConfig.kt:158, a default left out as
        // kotlinx.serialization leaves it out.
        membership?.let { writeFile(CONFIG_FILE, envelope(eventConfig(it))) }
        // v0.4:domain/feature/src/commonMain/kotlin/app/snapsync/feature/membership/DeviceManifestProducer.kt:93 — the
        // marker `"$eventId $json"`, at v0.4:domain/services/src/commonMain/kotlin/app/snapsync/services/manifest/DeviceManifestService.kt:9,12.
        now.ledger.of("uploadedManifest").singleOrNull()?.let {
            writeFile(MANIFEST_FILE, "${it["eventId"]} ${it["manifest"]}")
        }
        // v0.4:domain/services/src/commonMain/kotlin/app/snapsync/services/leave/PendingLeaves.kt:67,73 — one event per
        // line, sorted: 0.4 never recorded whether the device had received everything.
        now.ledger.of("pendingLeave").map { it.getValue("eventId")!! }.takeIf { it.isNotEmpty() }
            ?.let { writeFile(PENDING_LEAVES_FILE, it.sorted().joinToString("\n")) }
        // v0.4:domain/services/src/commonMain/kotlin/app/snapsync/services/album/AlbumMapService.kt:16,19,56,79 — the map
        // event → album, and the events whose album has held a photo, as JSON.
        val albums = now.ledger.of("eventAlbum")
        if (albums.isNotEmpty()) {
            preferences.set(
                ALBUM_MAP_KEY,
                buildJsonObject { albums.forEach { put(it.getValue("eventId")!!, it["albumLocalId"]) } }.toString(),
            )
            preferences.set(
                ALBUM_FILLED_KEY,
                buildJsonArray {
                    albums.filter { it["filled"] == "1" }.forEach { add(JsonPrimitive(it["eventId"])) }
                }.toString(),
            )
        }
        // v0.4:domain/services/src/commonMain/kotlin/app/snapsync/services/wake/EventChecks.kt:53,66 — epoch millis per
        // check and event.
        now.ledger.of("eventCheck").forEach {
            preferences.set("$CHECK_KEY_PREFIX.${it["kind"]}.${it["eventId"]}", it.getValue("lastAt")!!)
        }
        // v0.4:domain/services/src/commonMain/kotlin/app/snapsync/services/push/PushRegistrationRecord.kt:39 — a file,
        // where the next build keeps a Preferences key.
        (preferences.get(PUSH_RECORD_KEY) as? PrefRead.Value)?.let {
            writeFile(PUSH_RECORD_FILE, it.value)
            preferences.remove(PUSH_RECORD_KEY)
        }
    }

    /** What the first open of the build that moved the records left of their legacy homes: nothing. */
    private fun forgetLegacyRecords(now: Held) {
        listOf(CONFIG_FILE, MANIFEST_FILE, PENDING_LEAVES_FILE).forEach { disk.remove(FileArea.SHARED, it) }
        listOf(ALBUM_MAP_KEY, ALBUM_FILLED_KEY).forEach(preferences::remove)
        now.ledger.of("eventCheck").forEach { preferences.remove("$CHECK_KEY_PREFIX.${it["kind"]}.${it["eventId"]}") }
        disk.read(FileArea.SHARED, PUSH_RECORD_FILE)?.let {
            preferences.set(PUSH_RECORD_KEY, it.decodeToString())
            disk.remove(FileArea.SHARED, PUSH_RECORD_FILE)
        }
    }

    /**
     * The own uploads in flight, as 0.4 started them: to the byte route that names no event, declaring 0.4, from a
     * staged file with no digest beside it. Answers their keys.
     */
    private fun startedByZeroFour(): List<String> {
        val session = world.device.uploadSession
        return session.view().live.filter { key ->
            session.startedEarlier(key) { target ->
                // v0.4:domain/model/src/commonMain/kotlin/app/snapsync/model/EdgeUploadRequestProvider.kt:87-95 — the
                // destination, its content type, its version and its bearer.
                target.copy(
                    url = withNoEvent(target.url),
                    headers = target.headers.filterKeys { it.lowercase() in ZERO_FOUR_HEADERS } +
                        (Limits.VERSION_HEADER to ZERO_FOUR),
                )
            }.also {
                // The digest beside a staged file came with resumable uploads (v0.6:domain/services/src/commonMain/kotlin/
                // app/snapsync/services/upload/SessionTransfer.kt:218); 0.4 staged the bytes alone, at
                // v0.4:domain/services/src/commonMain/kotlin/app/snapsync/services/upload/UploadTransferService.kt:31.
                disk.remove(FileArea.SHARED, "$UPLOAD_STAGING/$key$DIGEST_SUFFIX")
            }
        }
    }

    // ---- downloads.db -------------------------------------------------------------------------------------------

    /** What the build at downloads.db [version] wrote on top of what it found — applied as the build passes [version]. */
    private fun downloadsLayer(version: Long, db: Tables, now: Held, inFlight: MutableList<String>) {
        when (version) {
            // 0.4's: a received photo tagged with the event whose union last served it, and a planned resource that
            // names no event (v0.4:domain/services/src/commonMain/sqldelight/download/app/snapsync/services/downloads/db/DownloadStore.sq:49,82);
            // its transfers in flight, described with no event.
            DOWNLOADS_BASELINE -> {
                val tags = now.downloads.of(
                    "eventRef",
                ).associate { (it["sourceDeviceId"] to it["sourceAssetId"]) to it["eventId"] }
                now.downloads.of("downloadAsset").forEach { row ->
                    db.insert("downloadAsset", row + ("eventId" to tags[row["sourceDeviceId"] to row["sourceAssetId"]]))
                }
                now.downloads.of("downloadResource").forEach { db.insert("downloadResource", it - "eventId") }
                inFlight += startedWithNoEvent()
            }
            // 5.sqm: where each event's union was last read to.
            DOWNLOADS_BASELINE + 1 -> now.downloads.of(
                "unionCursor",
            ).forEach { db.insert("unionCursor", it - "othersDeclared") }
            // 7.sqm: the memberships whose received photos were recognised.
            DOWNLOADS_BASELINE + 3 -> now.downloads.of("recognised").forEach { db.insert("recognised", it) }
            // 8.sqm: each union's declared total — this device's own declaration in it, which 11.sqm drops: what the
            // others declared now stands for it.
            DOWNLOADS_BASELINE + 4 -> now.downloads.of("unionCursor").forEach {
                db.write("UPDATE unionCursor SET declared = ? WHERE eventId = ?", it["othersDeclared"], it["eventId"])
            }
            else -> Unit
        }
    }

    /**
     * The downloads in flight, as 0.4 started them: described by source device, source asset and resource key alone
     * (v0.4:domain/services/src/commonMain/kotlin/app/snapsync/services/downloads/DownloadJobs.kt:31,36). Answers their
     * source assets.
     */
    private fun startedWithNoEvent(): List<String> {
        val session = world.device.downloads
        return session.view().inFlight.mapNotNull { started ->
            val fields = started.description.split(TAG_SEPARATOR)
            if (fields.size <= ZERO_FOUR_TAG_FIELDS) return@mapNotNull null
            val earlier = fields.take(ZERO_FOUR_TAG_FIELDS).joinToString(TAG_SEPARATOR)
            session.startedEarlier(started.description) { DownloadSessionMock.Started(it.url, earlier, it.network) }
            fields[1]
        }
    }

    // ---- the databases --------------------------------------------------------------------------------------------

    /** Every row of each table of database [name] as the device holds it now. */
    private fun held(name: String, schema: SqlSchema<QueryResult.Value<Unit>>): Rows {
        val opened = databases.inspectorPort().open(name, schema, readOnly = true)
        val database = (opened as? DbOpen.Opened)?.database ?: error("$name is not readable as this build's ($opened)")
        return when (val read = database.execute { Tables(it).snapshot() }) {
            is DbResult.Ok -> read.value
            is DbResult.Failed -> error("$name could not be read: ${read.detail}")
        }
    }

    /**
     * Database [name] put back at [target]: deleted, then built from [baseline] by [schema]'s own migrations, [layer]
     * adding what each version's build wrote as the build passes it.
     */
    private fun rebuild(
        name: String,
        schema: SqlSchema<QueryResult.Value<Unit>>,
        baseline: Long,
        target: Long,
        layer: (Long, Tables) -> Unit,
    ) {
        databases.delete(name)
        val older = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version = target

            override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
                schema.migrate(driver, 0, baseline)
                val db = Tables(driver)
                (baseline..target).forEach { version ->
                    if (version > baseline) schema.migrate(driver, version - 1, version)
                    layer(version, db)
                }
                return QueryResult.Value(Unit)
            }

            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
                QueryResult.Value(Unit)
        }
        val opened = databases.inspectorPort().open(name, older, readOnly = false)
        check(opened is DbOpen.Opened) { "$name at v$target did not build: $opened" }
    }

    // ---- the formats ----------------------------------------------------------------------------------------------

    /** The joined event's membership row as 0.4's EventConfig, every default left out. */
    private fun eventConfig(row: Map<String, String?>): JsonObject = buildJsonObject {
        put("eventId", row["eventId"])
        put("name", row["name"])
        put("minPhotoDate", row["minPhotoDate"])
        if (row["startsAt"] != row["minPhotoDate"]) put("startsAt", row["startsAt"])
        row["endsAt"]?.let { put("endsAt", it) }
        put("maxPhotoDate", row["maxPhotoDate"])
        row["deletesAt"]?.let { put("deletesAt", it) }
        if (row["direction"] != "Both") put("direction", row["direction"])
        if (row["saveToAlbum"] == "1") put("saveToAlbum", true)
        if (row["closed"] == "1") put("closed", true)
        val active = row["membersActive"]?.toInt()
        val settled = row["membersSettled"]?.toInt()
        if (active != null && settled != null) {
            put(
                "members",
                buildJsonObject {
                    put("active", active)
                    put("settled", settled)
                },
            )
        }
    }

    private fun envelope(payload: JsonObject): String = buildJsonObject {
        put("v", 1)
        put("payload", payload)
    }.toString()

    private fun writeFile(path: String, text: String) = disk.write(FileArea.SHARED, path, text.encodeToByteArray())

    /** What the device holds now: both databases, read before either is rebuilt. */
    private class Held(val ledger: Rows, val downloads: Rows) {
        /** The event the device is in, if any. */
        val joined: String? = ledger.of("membership").singleOrNull()?.get("eventId")
    }

    private companion object {
        /** 11.sqm: the oldest ledger.db the chain builds — 0.4's. */
        const val LEDGER_BASELINE = 12L

        /** 13.sqm: the ledger.db version whose build moved the shared records out of their legacy homes. */
        const val RECORDS_MOVED = 14L

        /** 4.sqm: the oldest downloads.db the chain builds — 0.4's. */
        const val DOWNLOADS_BASELINE = 5L

        /** A share range longer than any this build admits (`EventWindow.MAX`, 30 days). */
        val REFUSED_RANGE = 40.days

        /** The membership's columns 14.sqm added. */
        val OPEN_UNTIL_FACTS = setOf("openUntil", "membersWaiting", "deleted")

        const val CONFIG_FILE = "eventconfig.json"
        const val MANIFEST_FILE = "device-manifest/last-uploaded.json"
        const val PENDING_LEAVES_FILE = "membership/pending-leaves.txt"
        const val ALBUM_MAP_KEY = "app.snapsync.album.map"
        const val ALBUM_FILLED_KEY = "app.snapsync.album.filled"
        const val CHECK_KEY_PREFIX = "app.snapsync.check"
        const val PUSH_RECORD_FILE = "push-registration/last-registered.txt"
        const val PUSH_RECORD_KEY = "app.snapsync.push.lastRegistered"
        const val UPLOAD_STAGING = "upload-staging"
        const val DIGEST_SUFFIX = ".sha256"
        const val TAG_SEPARATOR = "\n"
        const val ZERO_FOUR_TAG_FIELDS = 3
        const val ZERO_FOUR = "0.4"
        val ZERO_FOUR_HEADERS = setOf("content-type", "authorization")

        /** A path or URL on the byte route with its event taken out: `…/events/<event>/files/…` → `…/files/…`. */
        fun withNoEvent(path: String): String = path.replace(Regex("/events/[^/]+/files/"), "/files/")
    }
}

/** A database's rows, read once: each table's, by column, every value as text. */
internal class Rows(private val tables: Map<String, List<Map<String, String?>>>) {
    fun of(table: String): List<Map<String, String?>> = tables[table].orEmpty()
}

/** Raw SQL over one database's driver: what a layer reads and writes, every value bound as text. */
internal class Tables(private val driver: SqlDriver) {
    /** Every row of every table, as [Rows]. */
    fun snapshot(): Rows = Rows(
        query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            1,
        ).associate { (table) ->
            val columns = query("SELECT name FROM pragma_table_info('$table')", 1).map { it.single()!! }
            table!! to query("SELECT ${columns.joinToString { "CAST($it AS TEXT)" }} FROM $table", columns.size)
                .map { values -> columns.zip(values).toMap() }
        },
    )

    /** One row into [table], its columns as named. */
    fun insert(table: String, row: Map<String, String?>) {
        val columns = row.keys.toList()
        write(
            "INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})",
            *columns.map(row::get).toTypedArray(),
        )
    }

    /** One statement, [values] bound in order as text — a column's affinity stores a number as one. */
    fun write(sql: String, vararg values: String?) {
        driver.execute(null, sql, values.size) { values.forEachIndexed { i, value -> bindString(i, value) } }
    }

    private fun query(sql: String, width: Int): List<List<String?>> = driver.executeQuery(null, sql, { cursor ->
        QueryResult.Value(buildList { while (cursor.next().value) add((0 until width).map(cursor::getString)) })
    }, 0).value
}
