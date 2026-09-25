package app.snapsync.contracts

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

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent

/** One canonical capture date for every row the ledger contracts build. */
internal const val CREATION_DATE = "2026-06-27T10:00:00Z"

/**
 * The guarded and pruning writes of the storage seam (capability `photo-sharing`): a record never overwrites a
 * settled row, and `deleteKeys` deletes exactly the rows it names. Part of [LedgerStoreContract]'s clause
 * list — a split for size only — so every [LedgerStore] binding runs these once. The helpers below are shared
 * with the other two parts.
 */
internal fun ClauseList<LedgerStoreState, LedgerStore>.recordGuardClauses() {
    clause("a record never overwrites a settled row", LedgerStoreState.EMPTY) { backend ->
        val settled = entry(state = LedgerState.COMPLETED)
        backend.recordUnlessSettled(settled)

        // A late REQUESTED (a second writer's duplicate job), a late failure (a stale retry, returning the row
        // to DISCOVERED), and a repeated COMPLETED with a different destination — each would move a finished
        // photo, and each is declined.
        for (late in listOf(
            entry(state = LedgerState.REQUESTED, destinationPath = "/late"),
            entry(state = LedgerState.DISCOVERED),
            entry(state = LedgerState.COMPLETED, destinationPath = "/other"),
        )) {
            assertFalse(backend.recordUnlessSettled(late), "${late.state} over COMPLETED must not apply")
            assertEquals(settled, backend.get(settled.key), "the settled row is unchanged field for field")
        }
    }

    clause("a record still moves a row between non-settled states", LedgerStoreState.EMPTY) { backend ->

        assertTrue(backend.recordUnlessSettled(entry(state = LedgerState.REQUESTED)))
        // A failed transfer: REQUESTED → DISCOVERED.
        assertTrue(backend.recordUnlessSettled(entry(state = LedgerState.DISCOVERED)))
        assertEquals(LedgerState.DISCOVERED, backend.get(entry().key)?.state)
        // A retry: DISCOVERED → REQUESTED at a fresh destination.
        assertTrue(backend.recordUnlessSettled(entry(state = LedgerState.REQUESTED, destinationPath = "/retry")))
        assertEquals(entry(state = LedgerState.REQUESTED, destinationPath = "/retry"), backend.get(entry().key))
        // And it can still finish.
        assertTrue(backend.recordUnlessSettled(entry(state = LedgerState.COMPLETED)))
        assertEquals(LedgerState.COMPLETED, backend.get(entry().key)?.state)
    }

    clause("a declined record does not ding", LedgerStoreState.EMPTY) { backend ->
        backend.recordUnlessSettled(entry(state = LedgerState.COMPLETED))
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        backend.recordUnlessSettled(entry(state = LedgerState.DISCOVERED))
        runCurrent()

        assertEquals(0, dings, "a write that changed nothing is no reason to re-read")
    }

    clause("resetTo still replaces settled rows", LedgerStoreState.EMPTY) { backend ->
        backend.recordUnlessSettled(entry(key = "a", state = LedgerState.COMPLETED))

        backend.resetTo(listOf(entry(key = "a", state = LedgerState.REQUESTED)))

        assertEquals(
            entry(key = "a", state = LedgerState.REQUESTED), backend.get("a"),
            "the reset family applies no precedence",
        )
    }

    clause("a settled row survives every writer record operation", LedgerStoreState.EMPTY) { backend ->
        val writer = LedgerWriter(backend)
        backend.seedCompleted(res("k", "A"))
        val settled = backend.get("k")

        assertFalse(writer.recordRequested(res("k", "A"), destinationPath = "/late"))
        assertFalse(writer.recordFailed(res("k", "A")))

        assertEquals(settled, backend.get("k"))
    }

    clause("deleteKeys deletes exactly the named rows and leaves an asset's siblings", LedgerStoreState.EMPTY) { backend ->
        val primary = entry(key = "X-primary.heic", assetId = "X", state = LedgerState.COMPLETED)
        backend.recordUnlessSettled(primary)
        backend.recordUnlessSettled(entry(key = "X-live.mov", assetId = "X", state = LedgerState.DISCOVERED))
        backend.recordUnlessSettled(entry(key = "Y-primary.heic", assetId = "Y", state = LedgerState.REQUESTED))

        // The Live Photo case: the paired video's key failed to resolve, and only that key is evidence.
        backend.deleteKeys(listOf("X-live.mov", "Y-primary.heic"))

        assertNull(backend.get("X-live.mov"))
        assertNull(backend.get("Y-primary.heic"))
        assertEquals(primary, backend.get("X-primary.heic"), "the sibling survives, every field unchanged")
    }

    clause("deleteKeys dings once when it deleted and not at all when it matched nothing", LedgerStoreState.EMPTY) { backend ->
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

    clause("deleteKeys handles more keys than one statement binds", LedgerStoreState.EMPTY) { backend ->
        val keys = (0 until 1_200).map { "asset-$it-photo.jpg" }
        backend.resetTo(keys.map { entry(key = it, state = LedgerState.COMPLETED) } + entry(key = "kept"))

        backend.deleteKeys(keys)

        assertEquals(listOf("kept"), backend.manifestRows().map { it.key })
    }

    clause("recordAllUnlessSettled applies each entry under the settled guard and dings once", LedgerStoreState.EMPTY) { backend ->
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

    clause("recordAllUnlessSettled that applies nothing does not ding", LedgerStoreState.EMPTY) { backend ->
        backend.recordUnlessSettled(entry(key = "done", state = LedgerState.COMPLETED))
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        assertEquals(0, backend.recordAllUnlessSettled(listOf(entry(key = "done", state = LedgerState.DISCOVERED))))
        assertEquals(0, backend.recordAllUnlessSettled(emptyList()))
        runCurrent()

        assertEquals(0, dings)
    }
}

// assetId defaults to the key, so a test that doesn't care about grouping gets one photo per
// row (the historical per-row behaviour); multi-resource-photo tests pass an explicit assetId.
internal fun entry(
    key: String = "cloud-1-ios.photo.heic",
    assetId: String = key,
    state: LedgerState = LedgerState.REQUESTED,
    destinationPath: String? = null,
) = LedgerEntry(
    key, assetId, state,
    creationDate = CREATION_DATE,
    role = ResourceRole.PRIMARY,
    contentType = "image/heic",
    originalFilename = "IMG_0001.HEIC",
    destinationPath = destinationPath,
)

/** The resource whose recording produces [entry] — the writer takes resources now, not bare keys. */
internal fun res(key: String = "cloud-1-ios.photo.heic", assetId: String = key) = Resource(
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

// ── the record guard (capability `photo-sharing`, "Record operations") ──────────────────────────
