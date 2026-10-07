package app.snapsync.downloadstore

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.databases.opened
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.downloads.db.DownloadDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The download store's migrations since 0.4's v5 (the older ones are squashed: every device runs 0.4 or later). Each
 * must keep the permanent suppression rows (`createdLocalId`) — else a downloaded photo would lose its
 * do-not-re-upload marker and echo back into storage.
 */
class DownloadStoreMigrationTest {

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
