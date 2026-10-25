package app.snapsync.services.downloads

import app.snapsync.contracts.at
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.AssetRef
import app.snapsync.model.PlannedResource
import app.snapsync.model.SuppressionReadiness
import app.snapsync.ports.DbOpen
import app.snapsync.services.databases.DatabaseUnavailable
import app.snapsync.services.databases.databaseAt
import app.snapsync.services.downloads.db.DownloadDatabase
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The extension's read-only suppression view over the app's download store (capability `receiving-photos`): what
 * the app records is what the extension suppresses, and a store the updated app has not migrated yet pauses the
 * extension rather than being read as empty or migrated behind the app's back. The two processes open the same
 * database, as they do the same App-Group file on a phone.
 */
class SuppressionOverDownloadStoreTest {

    private val ref = AssetRef(sourceDeviceId = testDeviceId("device-b"), sourceAssetId = testAssetId("asset-9"))
    private val resource =
        PlannedResource("photo-9.heic", "https://example.invalid/9", "photo", "image/heic", "photo-9.heic")

    @Test
    fun `the extension suppresses what the app recorded`() = runTest {
        val databases = inMemoryDatabases()
        val app = DownloadService(databases)
        val extension = SuppressionService(databases)
        assertEquals(SuppressionReadiness.Ready, extension.readiness(), "no store yet: nothing was downloaded")
        assertEquals(emptySet(), extension.suppressedLocalIds())

        app.plan(ref, creationDate = "2026-08-08T12:00:00Z", resources = listOf(resource))
        app.recordCreatedLocalId(ref, testAssetId("local-1"))

        assertEquals(setOf(testAssetId("local-1")), extension.suppressedLocalIds())
    }

    @Test
    fun `the extension sees the recognition the app recorded - and none without a store`() = runTest {
        val databases = inMemoryDatabases()
        val extension = SuppressionService(databases)
        assertFalse(extension.isRecognised(testEventId("E1")), "no store: the app recognised nothing on this device")

        val app = DownloadService(databases)
        app.recognition.mark(testEventId("E1"))

        assertTrue(extension.isRecognised(testEventId("E1")))
        assertFalse(extension.isRecognised(testEventId("E2")))
    }

    @Test
    fun `an unmigrated store pauses the extension until the app migrates it - keeping every handle`() = runTest {
        val (databases, _) = databaseAt(
            DOWNLOADS_DB_NAME,
            DownloadDatabase.Schema.at(
                6,
                "INSERT INTO downloadAsset VALUES ('DEV-A', 'OLD', 'IMPORTED', '2026-08-08T12:00:00Z', 'LOCAL-OLD', 'E1')",
            ),
        )
        val extension = SuppressionService(databases)

        assertEquals(SuppressionReadiness.OldSchema, extension.readiness())
        assertEquals(SuppressionReadiness.OldSchema, extension.readiness(), "the extension migrated nothing")

        DownloadService(databases).counts() // the app's first use migrates it

        assertEquals(SuppressionReadiness.Ready, extension.readiness())
        assertEquals(
            setOf(testAssetId("LOCAL-OLD")),
            extension.suppressedLocalIds(),
            "the old suppression row survived",
        )
    }

    @Test
    fun `a store the extension cannot read never answers recognised`() = runTest {
        val (old, _) = databaseAt(DOWNLOADS_DB_NAME, DownloadDatabase.Schema.at(6))
        assertFailsWith<DatabaseUnavailable>(
            "an older schema is no answer",
        ) { SuppressionService(old).isRecognised(testEventId("E1")) }

        val locked = inMemoryDatabases(mapOf(DOWNLOADS_DB_NAME to DbOpen.Failed("locked")))
        assertFailsWith<DatabaseUnavailable>("nor is a store that will not open") {
            SuppressionService(locked).isRecognised(testEventId("E1"))
        }
    }
}
