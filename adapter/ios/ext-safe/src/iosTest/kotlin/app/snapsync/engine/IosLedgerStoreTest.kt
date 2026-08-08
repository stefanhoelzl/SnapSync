package app.snapsync.engine

import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.testsupport.fileExists
import app.snapsync.testsupport.withTempDirectory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS ledger's **placement** — that the native SQLite driver really opens its database where the
 * container says (capability `sync-ledger`).
 *
 * The store's row semantics are the shared `SqlDelightLedgerStore`'s, exercised by the storage
 * contract in `:test:world`. What is only true on this target, and only in this factory, is the
 * plumbing: the base path travels through `NativeSqliteDriver`'s `onConfiguration` into
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
class IosLedgerStoreTest {

    private fun entry(key: String, state: LedgerState = LedgerState.COMPLETED) = LedgerEntry(
        key = key,
        assetId = "asset-$key",
        state = state,
        attempt = 1,
        eventId = "event-1",
        creationDate = "2026-08-08T12:00:00Z",
    )

    @Test
    fun `the database file lands where the container says`() {
        withTempDirectory { dir ->
            // The driver opens lazily, so the file appears on first use rather than at construction.
            runBlocking { iosLedgerStore(basePath = dir).put(entry("photo-1.heic")) }

            assertTrue(
                fileExists("$dir/ledger.db"),
                "a driver that ignored the base path would open a private database and share nothing",
            )
        }
    }

    @Test
    fun `a row written through the store is read back`() {
        withTempDirectory { dir ->
            val store = iosLedgerStore(basePath = dir)

            runBlocking {
                store.put(entry("photo-1.heic"))

                val row = assertNotNull(store.get("photo-1.heic"))
                assertEquals(LedgerState.COMPLETED, row.state)
                assertEquals("asset-photo-1.heic", row.assetId)
            }
        }
    }

    @Test
    fun `an unrecorded key reads as no row`() {
        withTempDirectory { dir ->
            val store = iosLedgerStore(basePath = dir)

            runBlocking {
                assertNull(store.get("never-uploaded.heic"), "null is a fact about the ledger, not about storage")
            }
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
            runBlocking {
                iosLedgerStore(basePath = dir).put(entry("photo-1.heic"))

                val reopened = iosLedgerStore(basePath = dir)
                assertNotNull(
                    reopened.get("photo-1.heic"),
                    "the ledger is the only memory that a photo was already uploaded; losing it re-uploads " +
                        "the whole post-cutoff library",
                )
            }
        }
    }

    @Test
    fun `two containers are two separate ledgers`() {
        withTempDirectory { first ->
            withTempDirectory { second ->
                runBlocking {
                    iosLedgerStore(basePath = first).put(entry("photo-1.heic"))

                    assertNull(
                        iosLedgerStore(basePath = second).get("photo-1.heic"),
                        "if the base path were ignored both stores would be the SAME file and this would " +
                            "pass by accident everywhere else",
                    )
                }
            }
        }
    }
}
