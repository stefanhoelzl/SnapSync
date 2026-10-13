package app.snapsync.services.downloads

import app.snapsync.contracts.at
import app.snapsync.domain.asset.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.services.databases.databaseAt
import app.snapsync.services.downloads.db.DownloadDatabase
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
            DOWNLOADS_DB_NAME,
            DownloadDatabase.Schema.at(
                6,
                "INSERT INTO downloadAsset VALUES ('$DEV_A', 'IMP', 'IMPORTED', '2026-08-08T12:00:00Z', 'LOCAL-IMP', '$E1')",
                "INSERT INTO downloadAsset VALUES ('$DEV_A', 'PEND', 'PENDING', '2026-08-08T12:00:00Z', NULL, '$E1')",
                "INSERT INTO downloadAsset VALUES ('$DEV_A', 'OLD', 'PENDING', '2026-08-08T12:00:00Z', NULL, NULL)",
                "INSERT INTO downloadResource VALUES ('$DEV_A', 'PEND', 'pend-primary.heic', 'https://x.invalid/p', 'primary', " +
                    "'image/heic', 'P.HEIC', 'download-staging/DEV-A/pend-primary.heic', 1)",
                "INSERT INTO downloadResource VALUES ('$DEV_A', 'PEND', 'pend-live.mov', 'https://x.invalid/l', 'live', " +
                    "'video/quicktime', 'P.MOV', NULL, 0)",
                "INSERT INTO downloadResource VALUES ('$DEV_A', 'OLD', 'old-primary.heic', 'https://x.invalid/o', 'primary', " +
                    "'image/heic', 'O.HEIC', NULL, 0)",
            ),
        )

        val store = DownloadService(databases)

        assertEquals(setOf(AssetId("LOCAL-IMP")), store.suppressedLocalIds(), "the import marker survives")
        assertEquals(setOf(AssetId("LOCAL-IMP")), store.importedLocalIdsOf(testEventId(E1)))
        assertEquals(1, store.counts(testEventId(E1)).imported)
        assertEquals(2, store.counts(testEventId(E1)).stillArriving, "the untagged row is no event's")
        assertEquals(3, store.counts().stillArriving, "and still the device's")
        assertEquals(
            mapOf("pend-live.mov" to E1, "old-primary.heic" to ""),
            store.pendingDownloads().associate { it.resource.resourceKey to it.eventId },
        )
        assertEquals(
            listOf("download-staging/DEV-A/pend-primary.heic"),
            store.stagedResources(AssetRef(testDeviceId(DEV_A), AssetId("PEND"))).map { it.stagedPath },
            "a staged path is kept verbatim",
        )
        // v7 → v8 (`changes/archive/2026-10-11-close-share-back-gaps`): recognition starts empty, with no backfill — the next pass decides.
        assertFalse(store.recognition.isRecognised(testEventId(E1)), "no membership is recognised by the migration")
    }

    /**
     * v8 → v9 (change `count-declared-photos`): the union position gains the event's declared total, null until the
     * next read writes it — which reads exactly as before the column existed. The position itself is kept, so the
     * next push still reads only what is new.
     */
    @Test
    fun `v8 to v9 keeps each position and declares nothing until the next read`() = runTest {
        val (databases, _) = databaseAt(
            DOWNLOADS_DB_NAME,
            DownloadDatabase.Schema.at(8, "INSERT INTO unionCursor VALUES ('$E1', 5)"),
        )

        val store = DownloadService(databases)

        assertEquals(5, store.union.cursor(testEventId(E1)), "the position survives")
        assertEquals(null, store.union.declared(testEventId(E1)))
        assertEquals(null, store.counts(testEventId(E1)).declared)
    }
}

/** The fixture's event and device, as the canonical ids a device stores. */
private val E1 = testEventId("E1").value
private val DEV_A = testDeviceId("DEV-A").value
