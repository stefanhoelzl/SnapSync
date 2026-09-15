package app.snapsync.world

import app.snapsync.model.toLedgerRow
import app.snapsync.model.ResourceRole
import app.snapsync.model.Resource
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.LedgerAggregates
import app.snapsync.ports.LedgerStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.model.PendingResource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** One canonical capture date for every row the ledger contracts build. */
internal const val CREATION_DATE = "2026-06-27T10:00:00Z"

/**
 * The guarded writes of the storage seam (capability `sync-ledger`): a record never overwrites a settled
 * row, and `markPresent` brings an absent asset back. Run by every [LedgerStore] binding through
 * [LedgerStoreContract], which extends this class.
 */
abstract class LedgerRecordGuardContract {

    protected abstract fun createBackend(): LedgerStore

    // assetId defaults to the key, so a test that doesn't care about grouping gets one photo per
    // row (the historical per-row behaviour); multi-resource-photo tests pass an explicit assetId.
    // eventId defaults to the pre-provenance sentinel "" so tests that are not about provenance
    // stay readable; provenance tests pass an explicit eventId.
    protected fun entry(
        key: String = "cloud-1-ios.photo.heic",
        assetId: String = key,
        state: LedgerState = LedgerState.REQUESTED,
        attempt: Int = 0,
        eventId: String = "",
        destinationPath: String? = null,
    ) = LedgerEntry(
        key, assetId, state, attempt, eventId,
        creationDate = CREATION_DATE,
        role = ResourceRole.PRIMARY,
        contentType = "image/heic",
        originalFilename = "IMG_0001.HEIC",
        destinationPath = destinationPath,
    )

    /** The resource whose recording produces [entry] — the writer takes resources now, not bare keys. */
    protected fun res(key: String = "cloud-1-ios.photo.heic", assetId: String = key) = Resource(
        filename = key,
        assetId = assetId,
        contentType = "public.heic",
        metadata = mapOf(
            RESOURCE_META_CREATION_DATE to CREATION_DATE,
            RESOURCE_META_MIME to "image/heic",
            RESOURCE_META_ORIGINAL_FILENAME to "IMG_0001.HEIC",
        ),
        data = Unit,
    )

    // ── the record guard (capability `sync-ledger`, "Record operations") ──────────────────────────

    @Test
    fun `a record never overwrites a settled row`() = runTest {
        val backend = createBackend()
        val settled = entry(state = LedgerState.COMPLETED, attempt = 2, eventId = "E1")
        backend.recordUnlessSettled(settled)

        // A late REQUESTED (a second writer's duplicate job), a late FAILED (a stale retry), and a repeated
        // COMPLETED with a different attempt — each would move a finished photo, and each is declined.
        for (late in listOf(
            entry(state = LedgerState.REQUESTED, attempt = 0, eventId = "E2", destinationPath = "/late"),
            entry(state = LedgerState.FAILED, attempt = 5, eventId = "E2"),
            entry(state = LedgerState.COMPLETED, attempt = 7, eventId = "E2"),
        )) {
            assertFalse(backend.recordUnlessSettled(late), "${late.state} over COMPLETED must not apply")
            assertEquals(settled, backend.get(settled.key), "the settled row is unchanged field for field")
        }
    }

    @Test
    fun `a record still moves a row between non-settled states`() = runTest {
        val backend = createBackend()

        assertTrue(backend.recordUnlessSettled(entry(state = LedgerState.REQUESTED, attempt = 0)))
        // A stranded transfer: REQUESTED → FAILED.
        assertTrue(backend.recordUnlessSettled(entry(state = LedgerState.FAILED, attempt = 0)))
        assertEquals(LedgerState.FAILED, backend.get(entry().key)?.state)
        // A retry: FAILED → REQUESTED at the next attempt.
        assertTrue(backend.recordUnlessSettled(entry(state = LedgerState.REQUESTED, attempt = 1)))
        assertEquals(entry(state = LedgerState.REQUESTED, attempt = 1), backend.get(entry().key))
        // And it can still finish.
        assertTrue(backend.recordUnlessSettled(entry(state = LedgerState.COMPLETED, attempt = 1)))
        assertEquals(LedgerState.COMPLETED, backend.get(entry().key)?.state)
    }

    @Test
    fun `a record over a non-settled row clears its absence mark`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.FAILED))
        backend.markAbsent("A")

        // The walk re-derived the asset, so it is here again: the record states that, as the upsert always has.
        backend.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.REQUESTED, attempt = 1))

        assertEquals(false, backend.get("A-photo.jpg")?.absent)
    }

    @Test
    fun `a declined record does not ding`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(state = LedgerState.COMPLETED))
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        backend.recordUnlessSettled(entry(state = LedgerState.FAILED))
        runCurrent()

        assertEquals(0, dings, "a write that changed nothing is no reason to re-read")
    }

    @Test
    fun `resetTo still replaces settled rows`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "a", state = LedgerState.COMPLETED, attempt = 4))

        backend.resetTo(listOf(entry(key = "a", state = LedgerState.REQUESTED, attempt = 0)))

        assertEquals(
            entry(key = "a", state = LedgerState.REQUESTED, attempt = 0), backend.get("a"),
            "the reset family applies no precedence",
        )
    }

    @Test
    fun `a settled row survives every writer record operation`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend)
        backend.seedCompleted(res("k", "A"), eventId = "E1", attempt = 1)
        val settled = backend.get("k")

        assertFalse(writer.recordRequested(res("k", "A"), attempt = 0, eventId = "E2", destinationPath = "/late"))
        assertFalse(writer.recordFailed(res("k", "A"), attempt = 3, eventId = "E2"))

        assertEquals(settled, backend.get("k"))
    }

    @Test
    fun `markPresent clears a settled row's mark and nothing else`() = runTest {
        val backend = createBackend()
        val before = entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED, attempt = 2, eventId = "E1")
        backend.recordUnlessSettled(before)
        backend.recordUnlessSettled(entry(key = "A-video.mov", assetId = "A", state = LedgerState.FAILED))
        backend.recordUnlessSettled(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.COMPLETED))
        backend.markAbsent("A")
        backend.markAbsent("B")

        // A restored photo: its COMPLETED row is one no record write will ever reach again.
        backend.markPresent(listOf("A", "unknown"))

        assertEquals(before, backend.get("A-photo.jpg"), "un-marked, every other field untouched")
        assertEquals(false, backend.get("A-video.mov")?.absent, "every row of the asset, whatever its state")
        assertEquals(true, backend.get("B-photo.jpg")?.absent, "an asset not named stays absent")
        assertEquals(listOf("A-photo.jpg", "A-video.mov"), backend.manifestRows().map { it.key }.sorted())
    }

    @Test
    fun `markPresent dings once when it cleared a mark`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))
        backend.markAbsent("A")
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        backend.markPresent(listOf("A"))
        runCurrent()

        assertEquals(1, dings)
    }

    @Test
    fun `markPresent over assets that are not absent changes nothing and does not ding`() = runTest {
        val backend = createBackend()
        val row = entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED)
        backend.recordUnlessSettled(row)
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        // The every-cycle case: the walk saw assets, none of which was ever marked.
        backend.markPresent(listOf("A", "B"))
        backend.markPresent(emptyList())
        runCurrent()

        assertEquals(row, backend.get("A-photo.jpg"))
        assertEquals(0, dings)
    }

    @Test
    fun `markPresent handles more assets than one statement binds`() = runTest {
        val backend = createBackend()
        val ids = (0 until 1_200).map { "asset-$it" }
        backend.resetTo(ids.map { entry(key = "$it-photo.jpg", assetId = it, state = LedgerState.COMPLETED) })
        ids.forEach { backend.markAbsent(it) }

        backend.markPresent(ids)

        assertEquals(ids.size, backend.manifestRows().size, "every named asset is present again")
    }
}
