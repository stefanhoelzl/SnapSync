package app.snapsync.downloadstore

import app.snapsync.ports.AssetRef
import app.snapsync.ports.PlannedResource
import app.snapsync.testsupport.fileExists
import app.snapsync.testsupport.withTempDirectory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The download store's **placement**, and the one view the upload extension is given of it
 * (capability `download-store`).
 *
 * Same argument as `IosLedgerStoreTest`: the row semantics belong to the shared
 * `SqlDelightDownloadStore` and its storage contract, while what is only true here is that the base
 * path reaches `extendedConfig.basePath` and that this is a **different file** from the ledger.
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
class IosDownloadStoreTest {

    private val ref = AssetRef(sourceDeviceId = "device-b", sourceAssetId = "asset-9")

    private val resource = PlannedResource(
        resourceKey = "photo-9.heic",
        url = "https://example.invalid/photo-9.heic",
        role = "photo",
        contentType = "image/heic",
        originalFilename = "photo-9.heic",
    )

    @Test
    fun `the database file lands where the container says`() {
        withTempDirectory { dir ->
            // The driver opens lazily, so the file appears on first use rather than at construction.
            runBlocking { iosDownloadStore(basePath = dir).assetCount() }

            assertTrue(fileExists("$dir/downloads.db"))
        }
    }

    @Test
    fun `the download store is a separate file from the ledger`() {
        withTempDirectory { dir ->
            runBlocking { iosDownloadStore(basePath = dir).assetCount() }

            assertFalse(
                fileExists("$dir/ledger.db"),
                "one file with two writers is how a shared database gets corrupted on a device nobody " +
                    "is watching",
            )
        }
    }

    @Test
    fun `a planned download is read back as pending`() {
        withTempDirectory { dir ->
            val store = iosDownloadStore(basePath = dir)

            runBlocking {
                store.plan(ref, creationDate = "2026-08-08T12:00:00Z", resources = listOf(resource))

                val pending = store.pendingDownloads()
                assertEquals(1, pending.size)
                assertEquals(ref, pending.single().ref)
                assertEquals("photo-9.heic", pending.single().resource.resourceKey)
            }
        }
    }

    @Test
    fun `a store reopened over the same container sees what was written`() {
        withTempDirectory { dir ->
            runBlocking {
                iosDownloadStore(basePath = dir)
                    .plan(ref, creationDate = "2026-08-08T12:00:00Z", resources = listOf(resource))

                assertEquals(1, iosDownloadStore(basePath = dir).pendingDownloads().size)
            }
        }
    }

    /**
     * The extension's window. It is typed as the read-only projection at the composition root so the
     * extension is *compile-prevented* from reaching anything else — but the value it returns still
     * has to be the app's real one, read over WAL from the same file.
     */
    @Test
    fun `the suppression view reads the local ids the app recorded`() {
        withTempDirectory { dir ->
            val app = iosDownloadStore(basePath = dir)

            runBlocking {
                app.plan(ref, creationDate = "2026-08-08T12:00:00Z", resources = listOf(resource))
                app.recordCreatedLocalId(ref, "local-identifier-1")

                assertEquals(
                    setOf("local-identifier-1"),
                    iosSuppressionSource(basePath = dir).suppressedLocalIds(),
                    "an empty suppression set makes the upload cycle re-upload every photo this device " +
                        "downloaded from the event it downloaded them from",
                )
            }
        }
    }

    @Test
    fun `an asset this device never imported suppresses nothing`() {
        withTempDirectory { dir ->
            val store = iosDownloadStore(basePath = dir)

            runBlocking {
                store.plan(ref, creationDate = "2026-08-08T12:00:00Z", resources = listOf(resource))

                assertEquals(
                    emptySet(),
                    iosSuppressionSource(basePath = dir).suppressedLocalIds(),
                    "a planned-but-not-imported asset has no local id to suppress",
                )
            }
        }
    }
}
