@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent

package app.snapsync.data.sharing

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.SelectionSnapshot
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The selection a partial grant reads: unread — never an empty selection — until the observer's first emission, and
 * each emission seen by the withholding step before any reader can admit from it.
 */
class SelectionSnapshotsTest {

    private val a = LibraryAssets.photo("A", DURING)
    private val b = LibraryAssets.photo("B", DURING)
    private val c = LibraryAssets.photo("C", DURING)

    private fun SelectionState.ids(): List<AssetId>? = (this as? SelectionState.Read)?.facts()?.map { it.assetId }

    @Test
    fun `a selection is unread until one is published`() {
        val selection = SelectionSnapshots()
        assertSame(SelectionState.Unread, selection.state.value)
        assertNull(selection.read)
    }

    @Test
    fun `each selection is seen before it is published, and an empty emission is skipped`() = runTest {
        val snapshots = SelectionSnapshots()
        val before = mutableListOf<Pair<List<AssetId>?, List<AssetId>?>>()
        val published = mutableListOf<List<AssetId>?>()

        snapshots.published(
            flowOf(null, SelectionSnapshot(listOf(a, b)), null, SelectionSnapshot(listOf(c))),
            beforePublish = object : BeforePublish {
                override suspend fun before(selection: SelectionState.Read) {
                    yield() // the withholding step may suspend
                    before += selection.ids() to snapshots.state.value.ids()
                }
            },
        ).collect { published += snapshots.state.value.ids() }

        assertEquals<List<Pair<List<AssetId>?, List<AssetId>?>>>(
            listOf(ids("A", "B") to null, ids("C") to ids("A", "B")),
            before,
            "the withholding step sees each selection while readers still see the previous one",
        )
        assertEquals<List<List<AssetId>?>>(
            listOf(ids("A", "B"), ids("C")),
            published,
            "one emission per selection; a null is none",
        )
        assertEquals(ids("C"), snapshots.read?.ids(), "the latest selection is the one read")
    }

    @Test
    fun `waiting for a selection returns once one is read - an empty one is read`() = runTest {
        val selection = SelectionSnapshots()
        var read = false
        launch {
            selection.awaitRead()
            read = true
        }
        runCurrent()
        assertFalse(read, "nothing has been read yet")

        selection.published(flowOf(SelectionSnapshot(emptyList())), beforePublish = NothingWithheld).collect()
        runCurrent()
        assertTrue(read)
        assertEquals(emptyList(), assertIs<SelectionState.Read>(selection.state.value).facts())
    }

    @Test
    fun `only a partial grant waits for the selection, and the selection counts its photos`() = runTest {
        val selection = SelectionSnapshots()
        assertNull(selection.selectedPhotos, "an unread selection is no count")
        selection.awaitAnswerable(PhotoAccess.FULL)
        selection.awaitAnswerable(PhotoAccess.NONE)
        var answered = false
        launch {
            selection.awaitAnswerable(PhotoAccess.LIMITED)
            answered = true
        }
        runCurrent()
        assertFalse(answered, "a partial grant waits for its selection")

        selection.published(flowOf(SelectionSnapshot(listOf(a, b))), beforePublish = NothingWithheld).collect()
        runCurrent()
        assertTrue(answered)
        assertEquals(2, selection.selectedPhotos)
    }

    @Test
    fun `a partial grant's selection is answerable once, when it is read`() = runTest {
        val selection = SelectionSnapshots()
        assertEquals(listOf(Unit), selection.answerableOnce(PhotoAccess.FULL).toList(), "nothing to wait for")
        var answered = 0
        launch { selection.answerableOnce(PhotoAccess.LIMITED).collect { answered++ } }
        runCurrent()
        assertEquals(0, answered, "a partial grant waits for its selection")
        selection.published(flowOf(SelectionSnapshot(listOf(a))), beforePublish = NothingWithheld).collect()
        runCurrent()
        assertEquals(1, answered)
    }

    @Test
    fun `a selection's resources are those in hand for the ids asked`() = runTest {
        val resources = SelectionState.Read(listOf(a, b, c)).resources()
        assertEquals(listOf("A", "C"), resources.of(ids("C", "A", "GONE").toSet()).resolvedIds())
        assertEquals(emptyList(), resources.of(emptySet()).resolvedIds())
    }
}
