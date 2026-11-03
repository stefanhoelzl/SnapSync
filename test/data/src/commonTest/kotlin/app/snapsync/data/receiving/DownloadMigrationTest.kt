package app.snapsync.data.receiving

import app.snapsync.contracts.at
import app.snapsync.data.receiving.db.DownloadDatabase
import app.snapsync.mock.DatabasesMock
import app.snapsync.ports.DbOpen
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The download store's migrations (capability `receiving-photos`), from the shape the oldest device holds (v6),
 * entered through the port so the store's own open runs the chain — the path an upgrading phone takes. The rows that
 * matter are the suppression handles: a migration that lost an imported row's `createdLocalId` would let that photo
 * echo back into the event. That every migration also runs on each platform's own SQLite is `DatabasesContract`'s
 * `DOWNLOADS_SCHEMA_MIGRATES_FROM_ITS_FIRST_VERSION`.
 */
class DownloadMigrationTest {

    /** A store over `downloads.db` as [version] of the schema left it, holding [rows]. */
    private fun storeAt(version: Long, vararg rows: String): Store {
        val databases = DatabasesMock()
        assertIs<DbOpen.Opened>(
            databases.port().open(DOWNLOADS_DB_NAME, DownloadDatabase.Schema.at(version, *rows), readOnly = false),
        )
        return Store(databases)
    }

    /**
     * v6 → v7 (change `event-scoped-local-state`): the per-photo tag becomes one row per (event, photo), and a planned
     * resource takes its asset's event — or the empty one when it had none. No row is lost, no marker or state moves,
     * and a staged path is kept verbatim, so no staged file has to move.
     */
    @Test
    fun `v6 to v7 moves the event tag into its own table and onto each planned resource`() = runTest {
        val store = storeAt(
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
        )
        val e1 = testEventId(E1)

        assertEquals(setOf(testAssetId("LOCAL-IMP")), assertIs<HandlesRead.Ok>(store.received.handles()).ids)
        assertEquals(setOf(testAssetId("LOCAL-IMP")), store.counts.importedOf(e1).ok())
        assertEquals(1, store.counts.counts(e1).ok().imported)
        assertEquals(2, store.counts.counts(e1).ok().stillArriving, "the untagged row is no event's")
        assertEquals(3, store.counts.census().ok().stillArriving, "and still the device's")
        assertEquals(
            listOf("old-primary.heic:", "pend-live.mov:$E1", "pend-primary.heic:$E1"),
            store.strings("SELECT resourceKey || ':' || eventId FROM downloadResource ORDER BY resourceKey"),
        )
        assertEquals(
            listOf("download-staging/DEV-A/pend-primary.heic"),
            store.strings("SELECT stagedPath FROM downloadResource WHERE stagedPath IS NOT NULL"),
            "a staged path is kept verbatim",
        )
        // v7 → v8 (`changes/archive/2026-10-11-close-share-back-gaps`): recognition starts empty, with no backfill.
        assertSame(RecognitionRead.NotRecognised, store.received.recognition(e1), "no membership is recognised")
    }

    /**
     * v8 → v9 (change `count-declared-photos`): the union position gains the event's declared total, null until the
     * next read writes it — which reads exactly as before the column existed. The position itself is kept, so the
     * next push still reads only what is new.
     */
    @Test
    fun `v8 to v9 keeps each position and declares nothing until the next read`() = runTest {
        val store = storeAt(8, "INSERT INTO unionCursor VALUES ('$E1', 5)")

        val stored = store.plans.position(testEventId(E1)).ok()
        assertEquals(5L, stored.position, "the position survives")
        assertEquals(null, stored.othersDeclared)
        assertEquals(null, store.counts.counts(testEventId(E1)).ok().othersDeclared)
    }

    /**
     * v11 → v12 (change `count-declared-photos`): a union read stores what the OTHERS declared. A stored total is
     * dropped, not converted — what this device had declared when it was read is not kept — so the event awaits
     * nothing until its next read; the position is kept, so the next push still reads only what is new.
     */
    @Test
    fun `v11 to v12 keeps each position and drops the stored total`() = runTest {
        val store = storeAt(11, "INSERT INTO unionCursor VALUES ('$E1', 5, 200)")

        val stored = store.plans.position(testEventId(E1)).ok()
        assertEquals(5L, stored.position, "the position survives")
        assertEquals(null, stored.othersDeclared, "the total held this device's own photos")
    }

    /**
     * v12 → v13 (receiving moved into the layers): `downloadAsset` is re-created so its state column is the domain's
     * `DownloadState`. Nothing a device stores changes — every row and every column is copied as it is, the
     * suppression index with them — so an imported row is still a suppression handle, a pending one still downloads,
     * an unimportable one is still settled, and an unconfirmed marker is still adjudicated.
     */
    @Test
    fun `v12 to v13 re-creates the asset table keeping every row as it was`() = runTest {
        val store = storeAt(
            12,
            "INSERT INTO downloadAsset VALUES ('$DEV_A', 'IMP', 'IMPORTED', '2026-08-08T12:00:00Z', 'LOCAL-IMP')",
            "INSERT INTO downloadAsset VALUES ('$DEV_A', 'PEND', 'PENDING', '2026-08-08T12:00:00Z', NULL)",
            "INSERT INTO downloadAsset VALUES ('$DEV_A', 'BAD', 'UNIMPORTABLE', '2026-08-08T12:00:00Z', NULL)",
            "INSERT INTO downloadAsset VALUES ('$DEV_A', 'UNC', 'PENDING', '2026-08-08T12:00:00Z', 'LOCAL-UNC')",
            "INSERT INTO eventRef VALUES ('$E1', '$DEV_A', 'IMP')",
            "INSERT INTO eventRef VALUES ('$E1', '$DEV_A', 'PEND')",
            "INSERT INTO eventRef VALUES ('$E1', '$DEV_A', 'BAD')",
        )

        assertEquals(
            listOf(
                "BAD|UNIMPORTABLE|2026-08-08T12:00:00Z|",
                "IMP|IMPORTED|2026-08-08T12:00:00Z|LOCAL-IMP",
                "PEND|PENDING|2026-08-08T12:00:00Z|",
                "UNC|PENDING|2026-08-08T12:00:00Z|LOCAL-UNC",
            ),
            store.strings(
                "SELECT sourceAssetId || '|' || state || '|' || creationDate || '|' || IFNULL(createdLocalId, '') " +
                    "FROM downloadAsset ORDER BY sourceAssetId",
            ),
        )
        assertEquals(
            setOf(testAssetId("LOCAL-IMP"), testAssetId("LOCAL-UNC")),
            assertIs<HandlesRead.Ok>(store.received.handles()).ids,
        )
        assertEquals(listOf("LOCAL-UNC"), store.releases.unconfirmed().ok().map { it.createdLocalId.value })
        assertEquals(1, store.counts.counts(testEventId(E1)).ok().imported)
        assertEquals(2, store.counts.counts(testEventId(E1)).ok().stillArriving, "the unimportable row is settled")
        assertEquals(
            listOf("downloadAsset_createdLocalId"),
            store.strings(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'downloadAsset' AND sql IS NOT NULL",
            ),
        )
    }
}

/** The fixture's event and device, as the canonical ids a device stores. */
private val E1 = testEventId("E1").value
private val DEV_A = testDeviceId("DEV-A").value
