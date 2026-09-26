package app.snapsync.downloadstore

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.databases.JdbcDatabases
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.PlannedResource
import app.snapsync.model.SuppressionReadiness
import app.snapsync.services.downloads.DOWNLOADS_DB_NAME
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.downloads.SuppressionService
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The extension's read-only suppression view over a REAL download store (capability `receiving-photos`): what the
 * app records is what the extension suppresses, and a store the updated app has not migrated yet pauses the
 * extension rather than being read as empty or migrated behind the app's back.
 */
class SuppressionOverDatabaseTest {

    private val dir = Files.createTempDirectory("suppression").toFile().also(File::deleteOnExit)
    private val ref = AssetRef(sourceDeviceId = "device-b", sourceAssetId = AssetId("asset-9"))
    private val resource = PlannedResource("photo-9.heic", "https://example.invalid/9", "photo", "image/heic", "photo-9.heic")

    @Test
    fun `the extension suppresses what the app imported`() = runTest {
        val app = DownloadService(JdbcDatabases(dir))
        val extension = SuppressionService(JdbcDatabases(dir))
        assertEquals(SuppressionReadiness.Ready, extension.readiness(), "no store yet: nothing was downloaded")
        assertEquals(emptySet(), extension.suppressedLocalIds())

        app.plan(ref, creationDate = "2026-08-08T12:00:00Z", resources = listOf(resource))
        app.recordCreatedLocalId(ref, AssetId("local-1"))

        assertEquals(setOf(AssetId("local-1")), extension.suppressedLocalIds())
    }

    @Test
    fun `an unmigrated store pauses the extension until the app migrates it, keeping every handle`() = runTest {
        seedVersionOneStore(File(dir, DOWNLOADS_DB_NAME))
        val extension = SuppressionService(JdbcDatabases(dir))

        assertEquals(SuppressionReadiness.OldSchema, extension.readiness())
        assertEquals(SuppressionReadiness.OldSchema, extension.readiness(), "the extension migrated nothing")

        DownloadService(JdbcDatabases(dir)).counts() // the app's first use migrates it

        assertEquals(SuppressionReadiness.Ready, extension.readiness())
        assertEquals(setOf(AssetId("LOCAL-OLD")), extension.suppressedLocalIds(), "the old suppression row survived")
    }

    /** The store as the first shipped schema wrote it — no `creationDate`, version 1 — with one imported asset. */
    private fun seedVersionOneStore(file: File) {
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        listOf(
            "CREATE TABLE downloadAsset (sourceDeviceId TEXT NOT NULL, sourceAssetId TEXT NOT NULL, state TEXT NOT NULL, " +
                "createdLocalId TEXT, PRIMARY KEY (sourceDeviceId, sourceAssetId))",
            "CREATE TABLE downloadResource (sourceDeviceId TEXT NOT NULL, sourceAssetId TEXT NOT NULL, resourceKey TEXT NOT NULL, " +
                "url TEXT NOT NULL, role TEXT NOT NULL, contentType TEXT NOT NULL, originalFilename TEXT NOT NULL, stagedPath TEXT, " +
                "PRIMARY KEY (sourceDeviceId, sourceAssetId, resourceKey))",
            "INSERT INTO downloadAsset VALUES ('DEV-A', 'OLD', 'IMPORTED', 'LOCAL-OLD')",
            "PRAGMA user_version = 1",
        ).forEach { driver.execute(null, it, 0) }
        driver.close()
    }
}
