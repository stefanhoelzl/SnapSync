package app.snapsync.downloadstore

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.downloadstore.db.DownloadDatabase
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

        val store = SqlDelightDownloadStore(DownloadDatabase(driver))
        assertEquals(setOf("LOCAL-OLD"), store.suppressedLocalIds(), "suppression row survived the migration")
        assertEquals(1, store.importedCount())
        assertEquals(true, store.isImported(AssetRef("DEV-A", "OLD")))
    }
}
