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
 * The guarded and pruning writes of the storage seam (capability `sync-ledger`): a record never overwrites a
 * settled row, `deleteKeys` deletes exactly the rows it names, and `clearAbsenceMarks` makes the rows an
 * earlier build marked reachable again. Run by every [LedgerStore] binding through
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
        // An earlier build's mark, seeded verbatim: nothing sets it any more.
        backend.resetTo(listOf(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.FAILED).markedAbsent()))

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
    fun `deleteKeys deletes exactly the named rows and leaves an asset's siblings`() = runTest {
        val backend = createBackend()
        val primary = entry(key = "X-primary.heic", assetId = "X", state = LedgerState.COMPLETED, eventId = "E1")
        backend.recordUnlessSettled(primary)
        backend.recordUnlessSettled(entry(key = "X-live.mov", assetId = "X", state = LedgerState.DISCOVERED))
        backend.recordUnlessSettled(entry(key = "Y-primary.heic", assetId = "Y", state = LedgerState.FAILED))

        // The Live Photo case: the paired video's key failed to resolve, and only that key is evidence.
        backend.deleteKeys(listOf("X-live.mov", "Y-primary.heic"))

        assertNull(backend.get("X-live.mov"))
        assertNull(backend.get("Y-primary.heic"))
        assertEquals(primary, backend.get("X-primary.heic"), "the sibling survives, every field unchanged")
    }

    @Test
    fun `deleteKeys dings once when it deleted and not at all when it matched nothing`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        backend.deleteKeys(listOf("unknown"))
        backend.deleteKeys(emptyList())
        runCurrent()
        assertEquals(0, dings, "a delete that matched nothing changed no truth")

        backend.deleteKeys(listOf("A-photo.jpg"))
        runCurrent()
        assertEquals(1, dings)
    }

    @Test
    fun `deleteKeys handles more keys than one statement binds`() = runTest {
        val backend = createBackend()
        val keys = (0 until 1_200).map { "asset-$it-photo.jpg" }
        backend.resetTo(keys.map { entry(key = it, state = LedgerState.COMPLETED) } + entry(key = "kept"))

        backend.deleteKeys(keys)

        assertEquals(listOf("kept"), backend.manifestRows().map { it.key })
    }

    @Test
    fun `clearAbsenceMarks unmarks every row an earlier build marked and nothing else`() = runTest {
        val backend = createBackend()
        // An earlier build's mark, seeded verbatim: nothing sets it any more.
        val settled = entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED, attempt = 2, eventId = "E1")
        backend.resetTo(
            listOf(
                settled.markedAbsent(),
                entry(key = "A-video.mov", assetId = "A", state = LedgerState.FAILED).markedAbsent(),
                entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.COMPLETED),
            ),
        )
        assertEquals(listOf("B-photo.jpg"), backend.manifestRows().map { it.key }, "marked rows are unreachable")

        backend.clearAbsenceMarks()

        assertEquals(settled, backend.get("A-photo.jpg"), "unmarked, every other field untouched")
        assertEquals(
            listOf("A-photo.jpg", "A-video.mov", "B-photo.jpg"),
            backend.manifestRows().map { it.key }.sorted(),
        )
        assertEquals(listOf("A-video.mov"), backend.rowsNeedingJob().map { it.key }, "back in the work read")
    }

    @Test
    fun `clearAbsenceMarks dings once when it cleared and not at all on a clean ledger`() = runTest {
        val backend = createBackend()
        backend.resetTo(listOf(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED).markedAbsent()))
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        backend.clearAbsenceMarks()
        runCurrent()
        assertEquals(1, dings)

        // The every-cycle case after the first: nothing is marked.
        backend.clearAbsenceMarks()
        runCurrent()
        assertEquals(1, dings)
    }

    @Test
    fun `recordAllUnlessSettled applies each entry under the settled guard and dings once`() = runTest {
        val backend = createBackend()
        val settled = entry(key = "done", state = LedgerState.COMPLETED)
        backend.recordUnlessSettled(settled)
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        val applied = backend.recordAllUnlessSettled(
            listOf(
                entry(key = "X-primary.heic", assetId = "X", state = LedgerState.DISCOVERED),
                entry(key = "X-live.mov", assetId = "X", state = LedgerState.DISCOVERED),
                entry(key = "done", state = LedgerState.DISCOVERED),
            ),
        )
        runCurrent()

        assertEquals(2, applied, "the settled row declines, exactly as the single write would")
        assertEquals(settled, backend.get("done"))
        assertEquals(LedgerState.DISCOVERED, backend.get("X-live.mov")?.state)
        assertEquals(1, dings, "one ding for the batch")
    }

    @Test
    fun `recordAllUnlessSettled that applies nothing does not ding`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "done", state = LedgerState.COMPLETED))
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        assertEquals(0, backend.recordAllUnlessSettled(listOf(entry(key = "done", state = LedgerState.DISCOVERED))))
        assertEquals(0, backend.recordAllUnlessSettled(emptyList()))
        runCurrent()

        assertEquals(0, dings)
    }
}
