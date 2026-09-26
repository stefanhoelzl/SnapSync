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
}
