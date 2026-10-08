package app.snapsync.services.ledger

import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.AssetId
import app.snapsync.model.LedgerAggregates
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.PendingResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.TerminalOutcome
import app.snapsync.ports.DbOpen
import app.snapsync.services.ledger.db.LedgerDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The upload ledger (capability `photo-sharing`): one row per resource of the joined event, the record of what this
 * device has discovered, asked the platform to upload, and seen settle. Over the `Databases` mock — real SQLite,
 * bound to the same `Databases` contract as every platform's adapter; that each platform's SQLite runs every
 * statement is that contract's schema clauses.
 *
 * The rules, in the order below: every read and write is scoped to the JOINED event; a record never overwrites a
 * SETTLED row; a photo counts complete only when every resource is; the platform's terminal write is guarded on
 * REQUESTED; the manifest declares intent, whatever the state; the work read is DISCOVERED, unbounded, ordered;
 * the delete and reset families; and the manifest version that tells a republish from a no-op.
 */
class LedgerServiceTest {

    private val databases = inMemoryDatabases()
    private var joined: String? = JOINED
    private val ledger = LedgerService(databases) { joined }

    // ---- the joined event --------------------------------------------------------------------------------

    @Test
    fun `nothing joined - every read is empty and every write declines`() = runTest {
        ledger.recordUnlessSettled(entry(key = "k", state = LedgerState.REQUESTED, destinationPath = "/p"))
        joined = null

        assertNull(ledger.get("k"))
        assertNull(ledger.entryForDestination("/p"))
        assertEquals(LedgerAggregates(0, 0), ledger.aggregates())
        assertEquals(emptyMap(), ledger.assetProgress())
        assertEquals(emptyList(), ledger.pendingResources())
        assertEquals(emptyList(), ledger.manifestRows())
        assertEquals(emptyList(), ledger.rowsNeedingJob())
        assertFalse(ledger.recordUnlessSettled(entry(key = "n")), "a record with no event to file it under")
        assertEquals(0, ledger.recordAllUnlessSettled(listOf(entry(key = "n"))))
        assertFalse(ledger.markTerminal("k", TerminalOutcome.COMPLETED), "a platform callback after a leave")
        ledger.backfillManifestDetail(entry(key = "k"))
        ledger.deleteKeys(listOf("k"))

        joined = JOINED
        assertEquals(
            entry(key = "k", state = LedgerState.REQUESTED, destinationPath = "/p"),
            ledger.get("k"),
            "untouched",
        )
        assertNull(ledger.get("n"))
    }

    @Test
    fun `rows of an event this ledger is not joined to are invisible to every read and write`() = runTest {
        ledger.resetTo(OTHER, listOf(entry(key = "k", state = LedgerState.COMPLETED, destinationPath = "/p")))

        assertNull(ledger.get("k"), "a COMPLETED row of another event suppresses nothing")
        assertNull(ledger.entryForDestination("/p"))
        assertEquals(LedgerAggregates(0, 0), ledger.aggregates())
        assertTrue(ledger.pendingResources().isEmpty())
        assertTrue(ledger.manifestRows().isEmpty())
        assertFalse(ledger.markTerminal("k", TerminalOutcome.COMPLETED), "another event's row is never settled")
        // The joined event records the same key independently, and it is work.
        assertTrue(ledger.recordUnlessSettled(entry(key = "k", state = LedgerState.DISCOVERED)))
        assertEquals(listOf("k"), ledger.rowsNeedingJob().map { it.key })
    }

    @Test
    fun `purging keeps the named event's rows and deletes every other's`() = runTest {
        ledger.recordUnlessSettled(entry(key = "a"))

        ledger.purgeExcept(JOINED)
        assertEquals(LedgerState.REQUESTED, ledger.get("a")?.state)

        ledger.purgeExcept(OTHER)
        assertNull(ledger.get("a"))
    }

    // ---- the record write, and its settled guard ---------------------------------------------------------

    @Test
    fun `a recorded entry round-trips field for field - a missing role included`() = runTest {
        val full = entry(key = "A", state = LedgerState.COMPLETED, destinationPath = "/a")
        val bare = LedgerEntry("B", AssetId("B"), LedgerState.DISCOVERED)

        assertTrue(ledger.recordUnlessSettled(full), "a new key always applies")
        assertTrue(ledger.recordUnlessSettled(bare))

        assertEquals(full, ledger.get("A"))
        assertEquals(bare, ledger.get("B"), "no role is stored as none and read back as none")
        assertNull(ledger.get("never-put"))
    }

    @Test
    fun `a record never overwrites a settled row`() = runTest {
        val settled = entry(state = LedgerState.COMPLETED)
        ledger.recordUnlessSettled(settled)

        // A late REQUESTED (a second writer's duplicate job), a late failure (a stale retry), and a repeated
        // COMPLETED with a different destination — each would move a finished photo, and each is declined.
        for (late in listOf(
            entry(state = LedgerState.REQUESTED, destinationPath = "/late"),
            entry(state = LedgerState.DISCOVERED),
            entry(state = LedgerState.COMPLETED, destinationPath = "/other"),
        )) {
            assertFalse(ledger.recordUnlessSettled(late), "${late.state} over COMPLETED must not apply")
            assertEquals(settled, ledger.get(settled.key), "the settled row is unchanged field for field")
        }
    }

    @Test
    fun `a record still moves a row between non-settled states - and can finish it`() = runTest {
        assertTrue(ledger.recordUnlessSettled(entry(state = LedgerState.REQUESTED)))
        assertTrue(ledger.recordUnlessSettled(entry(state = LedgerState.DISCOVERED)), "a failed transfer")
        assertEquals(LedgerState.DISCOVERED, ledger.get(entry().key)?.state)
        assertTrue(
            ledger.recordUnlessSettled(entry(state = LedgerState.REQUESTED, destinationPath = "/retry")),
            "a retry",
        )
        assertEquals(entry(state = LedgerState.REQUESTED, destinationPath = "/retry"), ledger.get(entry().key))
        assertTrue(ledger.recordUnlessSettled(entry(state = LedgerState.COMPLETED)))
        assertEquals(LedgerState.COMPLETED, ledger.get(entry().key)?.state)
    }

    @Test
    fun `a batch record applies each entry under the settled guard and counts what applied`() = runTest {
        val settled = entry(key = "done", state = LedgerState.COMPLETED)
        ledger.recordUnlessSettled(settled)

        val applied = ledger.recordAllUnlessSettled(
            listOf(
                entry(key = "X-primary.heic", assetId = "X", state = LedgerState.DISCOVERED),
                entry(key = "X-live.mov", assetId = "X", state = LedgerState.DISCOVERED),
                entry(key = "done", state = LedgerState.DISCOVERED),
            ),
        )

        assertEquals(2, applied, "the settled row declines, exactly as the single write would")
        assertEquals(settled, ledger.get("done"))
        assertEquals(LedgerState.DISCOVERED, ledger.get("X-live.mov")?.state)
        assertEquals(0, ledger.recordAllUnlessSettled(listOf(entry(key = "done", state = LedgerState.DISCOVERED))))
        assertEquals(0, ledger.recordAllUnlessSettled(emptyList()))
    }

    @Test
    fun `a batch record that fails part-way records nothing from that batch`() = runTest {
        // The walk skips an asset whose rows all exist, so a batch that landed one role of a Live Photo and not
        // the other would leave the other unrecorded for good. Force a failure on the batch's second statement
        // with a trigger — the real SQLite's rollback — and assert the first never landed.
        val driver = assertIs<DbOpen.Opened>(
            databases.open(LEDGER_DB_NAME, LedgerDatabase.Schema, readOnly = false),
        ).driver
        driver.execute(
            null,
            "CREATE TRIGGER boom BEFORE INSERT ON ledgerRow WHEN NEW.key = 'X-live.mov' BEGIN SELECT RAISE(ABORT, 'boom'); END",
            0,
        )

        val thrown = runCatching {
            ledger.recordAllUnlessSettled(
                listOf(
                    LedgerEntry("X-primary.heic", AssetId("X"), LedgerState.DISCOVERED),
                    LedgerEntry("X-live.mov", AssetId("X"), LedgerState.DISCOVERED),
                ),
            )
        }

        assertTrue(thrown.isFailure, "the injected failure surfaces")
        assertNull(ledger.get("X-primary.heic"), "the batch rolled back whole")
    }

    // ---- the destination a job was addressed to -----------------------------------------------------------

    @Test
    fun `a row is found by the destination its own request carried - across a route change`() = runTest {
        // An update that changes the upload route leaves jobs an earlier build created addressed to the OLD
        // destination; each row answers the destination ITS request carried.
        val before = "/api/v2/files/devices/D/old-1/primary"
        val after = "/api/v2/events/E/files/devices/D/new-1/primary"
        ledger.recordUnlessSettled(entry(key = "old-1-primary.heic", destinationPath = before))
        ledger.recordUnlessSettled(entry(key = "new-1-primary.heic", destinationPath = after))

        assertEquals("old-1-primary.heic", ledger.entryForDestination(before)?.key)
        assertEquals("new-1-primary.heic", ledger.entryForDestination(after)?.key)
        assertNull(ledger.entryForDestination("/api/v2/files/devices/D/never/primary"))
    }

    @Test
    fun `a row recorded without a destination is never matched and stays usable`() = runTest {
        // Every device carries such rows after an upgrade; the tier that reads them falls back to older recovery.
        ledger.recordUnlessSettled(entry())

        assertNull(ledger.entryForDestination("/api/v2/files/devices/D/cloud-1/primary"))
        assertEquals(entry(), ledger.get(entry().key))
    }

    // ---- counting: by photo, never by resource -------------------------------------------------------------

    @Test
    fun `an empty ledger counts nothing`() = runTest {
        assertEquals(LedgerAggregates(0, 0), ledger.aggregates())
        assertEquals(emptyMap(), ledger.assetProgress())
        assertEquals(emptyList(), ledger.pendingResources())
    }

    @Test
    fun `photos count by asset - complete only when every resource is`() = runTest {
        ledger.recordUnlessSettled(entry(key = "a", state = LedgerState.REQUESTED))
        ledger.recordUnlessSettled(entry(key = "b", state = LedgerState.DISCOVERED))
        ledger.recordUnlessSettled(entry(key = "c", state = LedgerState.COMPLETED))
        ledger.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))
        ledger.recordUnlessSettled(entry(key = "A-video.mov", assetId = "A", state = LedgerState.COMPLETED))
        ledger.recordUnlessSettled(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.COMPLETED))
        ledger.recordUnlessSettled(entry(key = "B-edit.jpg", assetId = "B", state = LedgerState.DISCOVERED))

        assertEquals(LedgerAggregates(pending = 3, completed = 2), ledger.aggregates())
        val progress = ledger.assetProgress()
        assertEquals(
            mapOf(
                AssetId("a") to false,
                AssetId("b") to false,
                AssetId("c") to true,
                AssetId("A") to true,
                AssetId("B") to false,
            ),
            progress,
        )
        assertEquals(ledger.aggregates().completed, progress.count { it.value }, "the two reads agree")
        assertEquals(
            setOf(
                PendingResource(AssetId("a"), "a"),
                PendingResource(AssetId("b"), "b"),
                PendingResource(AssetId("B"), "B-edit.jpg"),
            ),
            ledger.pendingResources().toSet(),
            "only the resources still outstanding, paired with their photo",
        )
    }

    // ---- the platform's terminal write ----------------------------------------------------------------------

    @Test
    fun `a completion settles a REQUESTED row everywhere at once and keeps every other column`() = runTest {
        ledger.recordUnlessSettled(entry(key = "a.heic", assetId = "A", destinationPath = "/a.heic"))

        assertTrue(ledger.markTerminal("a.heic", TerminalOutcome.COMPLETED), "it applied")

        // The party recording a terminal outcome is a platform callback holding nothing but the key.
        assertEquals(
            entry(key = "a.heic", assetId = "A", state = LedgerState.COMPLETED, destinationPath = "/a.heic"),
            ledger.get("a.heic"),
        )
        assertEquals(LedgerAggregates(pending = 0, completed = 1), ledger.aggregates())
        assertEquals(mapOf(AssetId("A") to true), ledger.assetProgress())
        assertEquals(emptyList(), ledger.pendingResources())
        assertEquals(emptyList(), ledger.rowsNeedingJob())
        assertEquals(listOf("a.heic"), ledger.manifestRows().map { it.key }, "and it is still declared")
    }

    @Test
    fun `a failed terminal outcome returns the row to the work read`() = runTest {
        ledger.recordUnlessSettled(entry(key = "a.heic", assetId = "A", destinationPath = "/a.heic"))

        assertTrue(ledger.markTerminal("a.heic", TerminalOutcome.FAILED))

        assertEquals(LedgerState.DISCOVERED, ledger.get("a.heic")?.state, "a failure is recorded as needing a job")
        assertEquals("/a.heic", ledger.get("a.heic")?.destinationPath, "every other column preserved")
        assertEquals(listOf("a.heic"), ledger.rowsNeedingJob().map { it.key })
    }

    @Test
    fun `the terminal write touches only a REQUESTED row and never resurrects one`() = runTest {
        ledger.recordUnlessSettled(entry(key = "done.heic", state = LedgerState.COMPLETED))
        ledger.recordUnlessSettled(entry(key = "found.heic", state = LedgerState.DISCOVERED))

        // Guarded on REQUESTED: that is what lets the walk write a row without racing the platform's delegate.
        assertFalse(ledger.markTerminal("done.heic", TerminalOutcome.FAILED))
        assertFalse(ledger.markTerminal("found.heic", TerminalOutcome.COMPLETED))
        assertFalse(ledger.markTerminal("ghost.heic", TerminalOutcome.COMPLETED))

        assertEquals(LedgerState.COMPLETED, ledger.get("done.heic")?.state, "and clobbered nothing")
        assertEquals(LedgerState.DISCOVERED, ledger.get("found.heic")?.state)
        assertNull(ledger.get("ghost.heic"), "a pruned row stays pruned")
    }

    // ---- the manifest: what this device INTENDS to provide --------------------------------------------------

    @Test
    fun `the manifest declares every row whatever its state`() = runTest {
        ledger.recordUnlessSettled(entry(key = "done.heic", state = LedgerState.COMPLETED))
        ledger.recordUnlessSettled(entry(key = "inflight.heic", state = LedgerState.REQUESTED))
        ledger.recordUnlessSettled(entry(key = "found.heic", state = LedgerState.DISCOVERED))
        // A row the join-time load seeded from a stored-file listing: COMPLETED, no capture date, no role. The read
        // does not exclude it — the membership's policy does (capability `photo-sharing`).
        ledger.recordUnlessSettled(LedgerEntry("seeded.heic", AssetId("C"), LedgerState.COMPLETED))

        val rows = ledger.manifestRows()

        assertEquals(listOf("done.heic", "found.heic", "inflight.heic", "seeded.heic"), rows.map { it.key }.sorted())
        assertEquals(
            LedgerEntry(
                "found.heic",
                AssetId("found.heic"),
                LedgerState.DISCOVERED,
                creationDate = CREATION_DATE,
                role = ResourceRole.PRIMARY,
                contentType = "image/heic",
                originalFilename = "IMG_0001.HEIC",
            ),
            rows.single { it.key == "found.heic" },
            "the projection carries the detail, and no destination",
        )
        assertNull(rows.single { it.key == "seeded.heic" }.role)
    }

    @Test
    fun `the backfill fills a bare row once and never overwrites detail`() = runTest {
        ledger.recordUnlessSettled(
            LedgerEntry("seeded.heic", AssetId("C"), LedgerState.COMPLETED, destinationPath = "/s"),
        )

        ledger.backfillManifestDetail(entry(key = "seeded.heic", assetId = "C", state = LedgerState.DISCOVERED))
        val filled = ledger.get("seeded.heic")!!
        assertEquals(CREATION_DATE, filled.creationDate)
        assertEquals(ResourceRole.PRIMARY, filled.role)
        assertEquals(
            LedgerState.COMPLETED,
            filled.state,
            "the sweep touches the detail only — state is not its business",
        )
        assertEquals("/s", filled.destinationPath)

        ledger.backfillManifestDetail(
            LedgerEntry("seeded.heic", AssetId("C"), LedgerState.COMPLETED, creationDate = "2099-01-01T00:00:00Z"),
        )
        assertEquals(
            CREATION_DATE,
            ledger.get("seeded.heic")!!.creationDate,
            "idempotent: a second sweep changes nothing",
        )
    }

    // ---- the work read --------------------------------------------------------------------------------------

    @Test
    fun `the work read is every DISCOVERED row - unbounded - in key order`() = runTest {
        for (k in listOf("c.heic", "a.heic", "d.heic", "b.heic")) {
            ledger.recordUnlessSettled(entry(key = k, state = LedgerState.DISCOVERED))
        }
        ledger.recordUnlessSettled(entry(key = "r.heic", state = LedgerState.REQUESTED))
        ledger.recordUnlessSettled(entry(key = "e.heic", state = LedgerState.COMPLETED))

        // REQUESTED has a job and COMPLETED is settled — neither is work. Ordered so a caller's slice is
        // deterministic, and UNBOUNDED: the cycle bounds what it RESOLVES, after admitting the rows against the
        // policy; a bound here would let excluded rows sorting first fill the slice on every cycle.
        assertEquals(listOf("a.heic", "b.heic", "c.heic", "d.heic"), ledger.rowsNeedingJob().map { it.key })
        assertEquals(LedgerAggregates(pending = 5, completed = 1), ledger.aggregates(), "and every one is backlog")
    }

    // ---- the delete and reset families ---------------------------------------------------------------------

    @Test
    fun `deleting keys deletes exactly the named rows and leaves an asset's siblings`() = runTest {
        val primary = entry(key = "X-primary.heic", assetId = "X", state = LedgerState.COMPLETED)
        ledger.recordUnlessSettled(primary)
        ledger.recordUnlessSettled(entry(key = "X-live.mov", assetId = "X", state = LedgerState.DISCOVERED))
        ledger.recordUnlessSettled(entry(key = "Y-primary.heic", assetId = "Y"))

        // The Live Photo case: the paired video's key failed to resolve, and only that key is evidence.
        ledger.deleteKeys(listOf("X-live.mov", "Y-primary.heic", "unknown"))
        ledger.deleteKeys(emptyList())

        assertNull(ledger.get("X-live.mov"))
        assertNull(ledger.get("Y-primary.heic"))
        assertEquals(primary, ledger.get("X-primary.heic"), "the sibling survives, every field unchanged")
    }

    @Test
    fun `deleting more keys than one statement binds deletes them all`() = runTest {
        val keys = (0 until 1_200).map { "asset-$it-photo.jpg" }
        ledger.resetTo(JOINED, keys.map { entry(key = it, state = LedgerState.COMPLETED) } + entry(key = "kept"))

        ledger.deleteKeys(keys)

        assertEquals(listOf("kept"), ledger.manifestRows().map { it.key })
    }

    @Test
    fun `a reset replaces every row with the baseline verbatim - settled ones included - and purges other events`() = runTest {
        ledger.recordUnlessSettled(entry(key = "old", state = LedgerState.COMPLETED))
        ledger.recordUnlessSettled(entry(key = "a", state = LedgerState.COMPLETED))
        LedgerService(databases) { OTHER }.recordUnlessSettled(entry(key = "elsewhere"))

        val baseline =
            listOf(entry(key = "a", state = LedgerState.REQUESTED), entry(key = "b", state = LedgerState.COMPLETED))
        ledger.resetTo(JOINED, baseline)

        assertNull(ledger.get("old"))
        assertEquals(baseline[0], ledger.get("a"), "the reset family applies no precedence")
        assertEquals(baseline[1], ledger.get("b"))
        assertNull(LedgerService(databases) { OTHER }.get("elsewhere"), "a reset is a join: no other event survives it")

        ledger.resetTo(JOINED, emptyList())
        assertEquals(LedgerAggregates(0, 0), ledger.aggregates())
    }

    @Test
    fun `a reset for another event takes the joined event's rows with the purge`() = runTest {
        ledger.recordUnlessSettled(entry(key = "joined"))

        ledger.resetTo(OTHER, emptyList())

        assertNull(ledger.get("joined"))
    }

    // ---- the manifest version: advances exactly when the projection changes ----------------------------------

    @Test
    fun `the version starts at zero and every inserted or deleted row advances it`() = runTest {
        assertEquals(0L, ledger.manifestVersion())

        ledger.recordUnlessSettled(entry())
        val inserted = ledger.manifestVersion()
        assertTrue(inserted > 0L)

        ledger.deleteKeys(listOf("never-recorded"))
        assertEquals(inserted, ledger.manifestVersion(), "a delete that matched nothing")

        ledger.deleteKeys(listOf(entry().key))
        assertTrue(ledger.manifestVersion() > inserted)
    }

    @Test
    fun `a change of a projected field advances the version and a change of state or destination does not`() = runTest {
        ledger.recordUnlessSettled(entry(state = LedgerState.DISCOVERED))
        val before = ledger.manifestVersion()

        // The manifest carries no upload state: a bump per finished upload would republish every cycle.
        ledger.recordUnlessSettled(entry(state = LedgerState.REQUESTED, destinationPath = "/d"))
        assertTrue(ledger.markTerminal(entry().key, TerminalOutcome.COMPLETED))
        assertEquals(before, ledger.manifestVersion())

        ledger.resetTo(JOINED, listOf(LedgerEntry("bare", AssetId("bare"), LedgerState.COMPLETED)))
        val bare = ledger.manifestVersion()
        ledger.backfillManifestDetail(entry(key = "bare"))
        assertTrue(ledger.manifestVersion() > bare, "a detail backfill changes what is declared")

        ledger.recordUnlessSettled(entry(key = "live", state = LedgerState.DISCOVERED))
        val live = ledger.manifestVersion()
        ledger.recordUnlessSettled(
            LedgerEntry(
                "live",
                AssetId("live"),
                LedgerState.DISCOVERED,
                creationDate = CREATION_DATE,
                role = ResourceRole.LIVE,
                contentType = "video/quicktime",
                originalFilename = "IMG_0001.MOV",
            ),
        )
        assertTrue(ledger.manifestVersion() > live, "a role change changes what is declared")
    }

    @Test
    fun `a declined record leaves the version alone`() = runTest {
        ledger.recordUnlessSettled(entry(state = LedgerState.COMPLETED))
        val before = ledger.manifestVersion()

        assertFalse(
            ledger.recordUnlessSettled(
                LedgerEntry(entry().key, AssetId("B"), LedgerState.DISCOVERED, creationDate = "2020-01-01T00:00:00Z"),
            ),
        )

        assertEquals(before, ledger.manifestVersion())
    }

    @Test
    fun `the reset family advances the version and never resets it - and a bump is exactly one`() = runTest {
        ledger.recordUnlessSettled(entry())
        val afterRecord = ledger.manifestVersion()
        ledger.clear()
        val afterClear = ledger.manifestVersion()
        assertTrue(afterClear > afterRecord, "clear deletes rows, so it advances")
        assertNull(ledger.get(entry().key), "and it deleted them")
        ledger.resetTo(JOINED, listOf(entry(key = "B"), entry(key = "C")))
        val afterReset = ledger.manifestVersion()
        assertTrue(afterReset > afterClear, "resetTo inserts rows, so it advances")

        ledger.bumpManifestVersion()

        assertEquals(afterReset + 1, ledger.manifestVersion())
    }
}

private const val JOINED = "E-joined"
private const val OTHER = "E-other"
private const val CREATION_DATE = "2026-06-27T10:00:00Z"

/** A fully described row; [assetId] defaults to the key, so a test that does not care gets one photo per row. */
private fun entry(
    key: String = "cloud-1-ios.photo.heic",
    assetId: String = key,
    state: LedgerState = LedgerState.REQUESTED,
    destinationPath: String? = null,
) = LedgerEntry(
    key,
    AssetId(assetId),
    state,
    creationDate = CREATION_DATE,
    role = ResourceRole.PRIMARY,
    contentType = "image/heic",
    originalFilename = "IMG_0001.HEIC",
    destinationPath = destinationPath,
)
