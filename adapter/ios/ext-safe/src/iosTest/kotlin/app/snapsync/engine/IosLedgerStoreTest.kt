package app.snapsync.engine

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.RowRead
import app.snapsync.data.upload.UploadRowsReset
import app.snapsync.databases.IosDatabases
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.upload.MintsStoredBytes
import app.snapsync.domain.upload.StoredBytes
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testEventId
import app.snapsync.testsupport.fileExists
import app.snapsync.testsupport.withTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The iOS ledger's **placement** — that the native SQLite driver really opens its database where the
 * container says (capability `photo-sharing`).
 *
 * The store's row semantics are the data layer's upload rows', exercised over the real schema on the JVM
 * (`OwnUploadRowsTest`) and by the storage contract bound beside the mocks. What is only true on this target, and only
 * in this factory, is the plumbing: the base path travels through `NativeSqliteDriver`'s `onConfiguration` into
 * `extendedConfig.basePath`, a nested copy whose failure mode is not an error but a database opened
 * **somewhere else** — the driver's own default location, inside the process's private sandbox rather
 * than the shared App Group.
 *
 * That failure is invisible from inside one process. Every read and write succeeds against the wrong
 * file; the app and the upload extension simply stop sharing an upload memory, so the extension
 * re-uploads what the app already recorded and the status screen counts a backlog that is not there.
 * Both assertions below are aimed at exactly that: the file must exist at the given path, and a store
 * re-opened over the same path must see the first one's rows.
 */
@OptIn(MintsStoredBytes::class)
class IosLedgerStoreTest {

    private val event = testEventId("E-joined")

    private fun sharedIn(dir: String) = SharedDatabase(IosDatabases(dir), legacy = null)

    private fun key(name: String) = UploadKey.of(testAssetId("asset-$name"), ResourceRole.PRIMARY, "$name.heic")

    /** [name]'s row written settled into the shared database in [dir]. */
    private fun write(dir: String, name: String) =
        assertEquals(StoreWrite.Written, UploadRowsReset(sharedIn(dir)).seed(event, listOf(StoredBytes(key(name)))))

    private fun read(dir: String, name: String): RowRead = OwnUploadRows(sharedIn(dir)).row(event, key(name))

    @Test
    fun `the database file lands where the container says`() {
        withTempDirectory { dir ->
            // The driver opens lazily, so the file appears on first use rather than at construction.
            write(dir, "photo-1")

            assertTrue(
                fileExists("$dir/ledger.db"),
                "a driver that ignored the base path would open a private database and share nothing",
            )
        }
    }

    @Test
    fun `a row written through the store is read back`() {
        withTempDirectory { dir ->
            write(dir, "photo-1")

            val row = assertIs<RowRead.Found>(read(dir, "photo-1")).row
            assertIs<UploadRow.Completed>(row)
            assertEquals(testAssetId("asset-photo-1"), row.key.asset)
        }
    }

    @Test
    fun `an unrecorded key reads as no row`() {
        withTempDirectory { dir ->
            assertEquals(RowRead.Absent, read(dir, "never-uploaded"), "absence is a fact about the ledger, not storage")
        }
    }

    /**
     * The cross-process property, reduced to what a single-process test can observe: a second store
     * opened over the same container sees the first one's rows. On device that second store is the
     * *other* process — the app reading what the extension wrote, or the reverse across a tier switch.
     */
    @Test
    fun `a store reopened over the same container sees what was written`() {
        withTempDirectory { dir ->
            write(dir, "photo-1")

            assertIs<RowRead.Found>(
                read(dir, "photo-1"),
                "the ledger is the only memory that a photo was already uploaded; losing it re-uploads " +
                    "the whole post-cutoff library",
            )
        }
    }

    @Test
    fun `two containers are two separate ledgers`() {
        withTempDirectory { first ->
            withTempDirectory { second ->
                write(first, "photo-1")

                assertEquals(
                    RowRead.Absent,
                    read(second, "photo-1"),
                    "if the base path were ignored both stores would be the SAME file and this would " +
                        "pass by accident everywhere else",
                )
            }
        }
    }
}
