package app.snapsync.services.downloads

import app.snapsync.contracts.DOWNLOADS_V6
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.PlannedResource
import app.snapsync.model.SuppressionReadiness
import app.snapsync.services.databases.databaseAt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The extension's read-only suppression view over the app's download store (capability `receiving-photos`): what
 * the app records is what the extension suppresses, and a store the updated app has not migrated yet pauses the
 * extension rather than being read as empty or migrated behind the app's back. The two processes open the same
 * database, as they do the same App-Group file on a phone.
 */
class SuppressionOverDownloadStoreTest {

    private val ref = AssetRef(sourceDeviceId = "device-b", sourceAssetId = AssetId("asset-9"))
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
        app.recordCreatedLocalId(ref, AssetId("local-1"))

        assertEquals(setOf(AssetId("local-1")), extension.suppressedLocalIds())
    }

    @Test
    fun `an unmigrated store pauses the extension until the app migrates it - keeping every handle`() = runTest {
        val (databases, _) = databaseAt(
            DOWNLOADS_DB_NAME,
            6,
            *DOWNLOADS_V6.toTypedArray(),
            "INSERT INTO downloadAsset VALUES ('DEV-A', 'OLD', 'IMPORTED', '2026-08-08T12:00:00Z', 'LOCAL-OLD', 'E1')",
        )
        val extension = SuppressionService(databases)

        assertEquals(SuppressionReadiness.OldSchema, extension.readiness())
        assertEquals(SuppressionReadiness.OldSchema, extension.readiness(), "the extension migrated nothing")

        DownloadService(databases).counts() // the app's first use migrates it

        assertEquals(SuppressionReadiness.Ready, extension.readiness())
        assertEquals(setOf(AssetId("LOCAL-OLD")), extension.suppressedLocalIds(), "the old suppression row survived")
    }
}
