package app.snapsync.downloadstore

import app.snapsync.data.receiving.DOWNLOADS_DB_NAME
import app.snapsync.data.receiving.DownloadDatabaseFile
import app.snapsync.data.receiving.HandlesRead
import app.snapsync.data.receiving.ReceivedPhotos
import app.snapsync.data.receiving.ReceivedPhotosReader
import app.snapsync.data.receiving.RecognitionRead
import app.snapsync.data.receiving.db.DownloadDatabase
import app.snapsync.databases.IosDatabases
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.ports.DbOpen
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import app.snapsync.testsupport.fileExists
import app.snapsync.testsupport.withTempDirectory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The download store's **placement**, and the one view the upload extension is given of it
 * (capability `receiving-photos`).
 *
 * Same argument as `IosLedgerStoreTest`: the row semantics belong to the shared data layer (`data/receiving`) and its
 * suites, while what is only true here is that the base path reaches `extendedConfig.basePath` and that this is a
 * **different file** from the ledger.
 *
 * The separation is not tidiness. Each store keeps a single writer per file — the ledger is
 * extension-written, this one is app-written — and WAL is what lets the extension read this store
 * concurrently. Two writers on one file is the arrangement that produces corruption on a device
 * nobody is watching.
 *
 * The suppression view is the sharper of the two tests. It is the extension's **only** window into
 * the download store, and what it carries is the set of local ids this device created by *importing*
 * a photo somebody else contributed. If that set came back empty, the upload cycle would treat every
 * downloaded photo as one of this device's own captures and upload it straight back into the event —
 * a loop that grows the union with duplicates and costs the user's data plan to do it.
 */
@OptIn(AppProcessOnly::class)
class IosDownloadStoreTest {

    private val event = testEventId("event-9")

    /** The app's view: the one writer, which creates and migrates the store. */
    private fun app(dir: String) = ReceivedPhotos(DownloadDatabaseFile(IosDatabases(dir)))

    /** A received photo's row as the app's store records it: imported onto [created], or planned with none. */
    private fun received(dir: String, created: String?) {
        val opened = IosDatabases(dir).open(DOWNLOADS_DB_NAME, DownloadDatabase.Schema, readOnly = false)
        val database = assertIs<DbOpen.Opened>(opened).database
        database.execute { driver ->
            driver.execute(null, ROW, 4) {
                bindString(0, testDeviceId("device-b").value)
                bindString(1, testAssetId("asset-9").value)
                bindString(2, if (created == null) "PENDING" else "IMPORTED")
                bindString(3, created?.let { testAssetId(it).value })
            }
        }
    }

    @Test
    fun `the database file lands where the container says`() {
        withTempDirectory { dir ->
            // The driver opens lazily, so the file appears on first use rather than at construction.
            runBlocking { app(dir).handles() }

            assertTrue(fileExists("$dir/downloads.db"))
        }
    }

    @Test
    fun `the download store is a separate file from the ledger`() {
        withTempDirectory { dir ->
            runBlocking { app(dir).handles() }

            assertFalse(
                fileExists("$dir/ledger.db"),
                "one file with two writers is how a shared database gets corrupted on a device nobody " +
                    "is watching",
            )
        }
    }

    @Test
    fun `a store reopened over the same container sees what was written`() {
        withTempDirectory { dir ->
            runBlocking {
                app(dir).markRecognised(event)

                assertIs<RecognitionRead.Recognised>(app(dir).recognition(event))
                assertIs<RecognitionRead.Recognised>(
                    ReceivedPhotosReader(IosDatabases(dir)).recognition(event),
                    "the extension reads the app's record read-only, over WAL from the same file",
                )
            }
        }
    }

    /**
     * The extension's window. It is typed as the read-only reader at the composition root so the
     * extension is *compile-prevented* from writing anything — but the value it returns still
     * has to be the app's real one, read over WAL from the same file.
     */
    @Test
    fun `the suppression view reads the local ids the app recorded`() {
        withTempDirectory { dir ->
            runBlocking {
                app(dir).handles()
                received(dir, created = "local-identifier-1")

                assertEquals(
                    setOf(testAssetId("local-identifier-1")),
                    assertIs<HandlesRead.Ok>(ReceivedPhotosReader(IosDatabases(dir)).handles()).ids,
                    "an empty suppression set makes the upload cycle re-upload every photo this device " +
                        "downloaded from the event it downloaded them from",
                )
            }
        }
    }

    @Test
    fun `an asset this device never imported suppresses nothing`() {
        withTempDirectory { dir ->
            runBlocking {
                app(dir).handles()
                received(dir, created = null)

                assertEquals(
                    emptySet(),
                    assertIs<HandlesRead.Ok>(ReceivedPhotosReader(IosDatabases(dir)).handles()).ids,
                    "a planned-but-not-imported asset has no local id to suppress",
                )
            }
        }
    }

    private companion object {
        const val ROW = "INSERT INTO downloadAsset (sourceDeviceId, sourceAssetId, state, creationDate, " +
            "createdLocalId) VALUES (?, ?, ?, '2026-08-08T12:00:00Z', ?)"
    }
}
