package app.snapsync.services.downloads

import app.snapsync.contracts.DOWNLOADS_V6
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.services.databases.databaseAt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The download store's migrations (capability `receiving-photos`), from the shape the oldest device holds (v6),
 * entered through the port so the service's own open runs the chain. The rows that matter are the
 * suppression handles: a migration that lost an imported row's `createdLocalId` would let that photo echo back into
 * the event. That every migration also runs on each platform's own SQLite is `DatabasesContract`'s
 * `DOWNLOADS_SCHEMA_MIGRATES_FROM_ITS_FIRST_VERSION`.
 */
class DownloadMigrationTest {

    /**
     * v6 → v7 (change `event-scoped-local-state`): the per-photo tag becomes one row per (event, photo), and a planned
     * resource takes its asset's event — or the empty one when it had none. No row is lost, no marker or state moves,
     * and a staged path is kept verbatim, so no staged file has to move.
     */
    @Test
    fun `v6 to v7 moves the event tag into its own table and onto each planned resource`() = runTest {
        val (databases, _) = databaseAt(
            DOWNLOADS_DB_NAME, 6,
            *DOWNLOADS_V6.toTypedArray(),
            "INSERT INTO downloadAsset VALUES ('DEV-A', 'IMP', 'IMPORTED', '2026-08-08T12:00:00Z', 'LOCAL-IMP', 'E1')",
            "INSERT INTO downloadAsset VALUES ('DEV-A', 'PEND', 'PENDING', '2026-08-08T12:00:00Z', NULL, 'E1')",
            "INSERT INTO downloadAsset VALUES ('DEV-A', 'OLD', 'PENDING', '2026-08-08T12:00:00Z', NULL, NULL)",
            "INSERT INTO downloadResource VALUES ('DEV-A', 'PEND', 'pend-primary.heic', 'https://x.invalid/p', 'primary', " +
                "'image/heic', 'P.HEIC', 'download-staging/DEV-A/pend-primary.heic', 1)",
            "INSERT INTO downloadResource VALUES ('DEV-A', 'PEND', 'pend-live.mov', 'https://x.invalid/l', 'live', " +
                "'video/quicktime', 'P.MOV', NULL, 0)",
            "INSERT INTO downloadResource VALUES ('DEV-A', 'OLD', 'old-primary.heic', 'https://x.invalid/o', 'primary', " +
                "'image/heic', 'O.HEIC', NULL, 0)",
        )

        val store = DownloadService(databases)

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
