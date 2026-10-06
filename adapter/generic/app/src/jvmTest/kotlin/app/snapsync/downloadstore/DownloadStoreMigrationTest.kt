package app.snapsync.downloadstore

import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.databases.opened
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.downloads.db.DownloadDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The v1 store (shipped before capture-date support) had no `creationDate` column. The 1.sqm migration
 * must ADD it **non-destructively** so the permanent suppression rows (`createdLocalId`) survive — else
 * a downloaded photo would lose its do-not-re-upload marker and echo back into storage.
 */
class DownloadStoreMigrationTest {

    @Test
    fun `v1 to v2 adds creationDate and preserves suppression rows`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Stand up the pre-migration (v1) schema with an IMPORTED suppression row.
        driver.execute(
            null,
            """
            CREATE TABLE downloadAsset (
                sourceDeviceId TEXT NOT NULL,
                sourceAssetId  TEXT NOT NULL,
                state          TEXT NOT NULL,
                createdLocalId TEXT,
                PRIMARY KEY (sourceDeviceId, sourceAssetId)
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE downloadResource (
                sourceDeviceId TEXT NOT NULL, sourceAssetId TEXT NOT NULL, resourceKey TEXT NOT NULL,
                url TEXT NOT NULL, role TEXT NOT NULL, contentType TEXT NOT NULL,
                originalFilename TEXT NOT NULL, stagedPath TEXT,
                PRIMARY KEY (sourceDeviceId, sourceAssetId, resourceKey)
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO downloadAsset VALUES ('DEV-A', 'OLD', 'IMPORTED', 'LOCAL-OLD')",
            0,
        )

        // Migrate v1 -> current; the suppression row must survive with its createdLocalId intact.
        DownloadDatabase.Schema.migrate(driver, 1L, DownloadDatabase.Schema.version).await()

        val store = DownloadService(opened(driver))
        assertEquals(setOf(AssetId("LOCAL-OLD")), store.suppressedLocalIds(), "suppression row survived the migration")
        assertEquals(1, store.counts().imported)
        assertEquals(true, store.isSettled(AssetRef("DEV-A", AssetId("OLD"))))
    }

    /**
     * v3 → v4 rewrites every staged path relative to the shared area (capability `receiving-photos`): a row that
     * kept the absolute container path would point at nothing once the container moved, and its bytes would read
     * as consumed. A path outside the staging directory is left as it was.
     */
    @Test
    fun `v3 to v4 makes staged paths relative and keeps everything else`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DownloadDatabase.Schema.create(driver)
        driver.execute(null, "INSERT INTO downloadAsset (sourceDeviceId, sourceAssetId, state, createdLocalId, creationDate) " +
            "VALUES ('DEV-A', 'A', 'PLANNED', NULL, '2026-08-08T12:00:00Z')", 0)
        val container = "/private/var/mobile/Containers/Shared/AppGroup/0B1C2D3E"
        listOf(
            "a-primary.heic" to "$container/download-staging/DEV-A/a-primary.heic",
            "a-live.mov" to null,
            "a-odd.jpg" to "/somewhere/else/a-odd.jpg",
        ).forEach { (key, path) ->
            driver.execute(null, "INSERT INTO downloadResource (sourceDeviceId, sourceAssetId, resourceKey, url, role, contentType, " +
                "originalFilename, stagedPath) VALUES ('DEV-A', 'A', '$key', 'https://x.invalid', 'primary', 'image/heic', '$key', " +
                (path?.let { "'$it'" } ?: "NULL") + ")", 0)
        }

        DownloadDatabase.Schema.migrate(driver, 3L, 4L).await()

        val paths = driver.executeQuery(null, "SELECT resourceKey, stagedPath FROM downloadResource", { c ->
            val out = mutableMapOf<String, String?>()
            while (c.next().value) out[c.getString(0)!!] = c.getString(1)
            app.cash.sqldelight.db.QueryResult.Value(out)
        }, 0).value
        assertEquals(
            mapOf(
                "a-primary.heic" to "download-staging/DEV-A/a-primary.heic",
                "a-live.mov" to null,
                "a-odd.jpg" to "/somewhere/else/a-odd.jpg",
            ),
            paths,
        )
    }

    /**
     * v6 → v7 (change `event-scoped-local-state`): the per-photo tag becomes one row per (event, photo), and a planned
     * resource takes its asset's event — or the empty one when it had none. No row is lost, no marker or state moves,
     * and a staged path is kept verbatim, so no staged file has to move.
     */
    @Test
    fun `v6 to v7 moves the event tag into its own table and onto each planned resource`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        V6_SCHEMA.forEach { driver.execute(null, it, 0) }
        listOf(
            "('DEV-A', 'IMP', 'IMPORTED', '2026-08-08T12:00:00Z', 'LOCAL-IMP', 'E1')",
            "('DEV-A', 'PEND', 'PENDING', '2026-08-08T12:00:00Z', NULL, 'E1')",
            "('DEV-A', 'OLD', 'PENDING', '2026-08-08T12:00:00Z', NULL, NULL)",
        ).forEach { driver.execute(null, "INSERT INTO downloadAsset VALUES $it", 0) }
        listOf(
            "('DEV-A', 'PEND', 'pend-primary.heic', 'https://x.invalid/p', 'primary', 'image/heic', 'P.HEIC', " +
                "'download-staging/DEV-A/pend-primary.heic', 1)",
            "('DEV-A', 'PEND', 'pend-live.mov', 'https://x.invalid/l', 'live', 'video/quicktime', 'P.MOV', NULL, 0)",
            "('DEV-A', 'OLD', 'old-primary.heic', 'https://x.invalid/o', 'primary', 'image/heic', 'O.HEIC', NULL, 0)",
        ).forEach { driver.execute(null, "INSERT INTO downloadResource VALUES $it", 0) }

        DownloadDatabase.Schema.migrate(driver, 6L, DownloadDatabase.Schema.version).await()

        val store = DownloadService(opened(driver))
        assertEquals(setOf(AssetId("LOCAL-IMP")), store.suppressedLocalIds(), "the import marker survives")
        assertEquals(setOf(AssetId("LOCAL-IMP")), store.importedLocalIdsOf("E1"))
        assertEquals(1, store.counts("E1").imported)
        assertEquals(2, store.counts("E1").stillArriving, "the untagged row is no event's")
        assertEquals(3, store.counts().stillArriving, "and still the device's")
        assertEquals(
            mapOf("pend-live.mov" to "E1", "old-primary.heic" to ""),
            store.pendingDownloads().associate { it.resource.resourceKey to it.eventId },
        )
        assertEquals(
            listOf("download-staging/DEV-A/pend-primary.heic"),
            store.stagedResources(AssetRef("DEV-A", AssetId("PEND"))).map { it.stagedPath },
            "a staged path is kept verbatim",
        )
    }
}

/** The v6 store — what 5.sqm leaves, and the shape 6.sqm meets. */
private val V6_SCHEMA = listOf(
    """
    CREATE TABLE downloadAsset (
        sourceDeviceId TEXT NOT NULL, sourceAssetId TEXT NOT NULL, state TEXT NOT NULL, creationDate TEXT NOT NULL,
        createdLocalId TEXT, eventId TEXT,
        PRIMARY KEY (sourceDeviceId, sourceAssetId)
    )
    """.trimIndent(),
    "CREATE INDEX downloadAsset_createdLocalId ON downloadAsset(createdLocalId)",
    """
    CREATE TABLE downloadResource (
        sourceDeviceId TEXT NOT NULL, sourceAssetId TEXT NOT NULL, resourceKey TEXT NOT NULL, url TEXT NOT NULL,
        role TEXT NOT NULL, contentType TEXT NOT NULL, originalFilename TEXT NOT NULL, stagedPath TEXT,
        enqueued INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (sourceDeviceId, sourceAssetId, resourceKey)
    )
    """.trimIndent(),
    "CREATE TABLE unionCursor (eventId TEXT NOT NULL PRIMARY KEY, cursor INTEGER NOT NULL)",
)
