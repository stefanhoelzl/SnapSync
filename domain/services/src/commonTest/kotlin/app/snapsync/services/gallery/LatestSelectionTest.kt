package app.snapsync.services.gallery

import app.snapsync.model.AssetId
import app.snapsync.model.Fact
import app.snapsync.model.GalleryAccess
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.SelectionScope
import app.snapsync.model.SelectionSnapshot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The latest selection snapshot of a partial grant (capability `photo-access`): `null` — "not read yet", never an
 * empty selection — until the observer's first emission; the walk-vs-snapshot answer derived per read from the grant;
 * a presence read under a partial grant waits for the first snapshot; and each emission becomes the snapshot before
 * its one consumer runs.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class LatestSelectionTest {

    private fun photo(id: String) = RawAsset(
        assetId = AssetId(id),
        creationDate = "2026-06-01T10:00:00Z",
        rawResources = listOf(RawResource(ResourceRole.PRIMARY, "image/jpeg", "IMG.JPG", Unit)),
    )

    @Test
    fun `an unread selection is unread under a partial grant and irrelevant under any other`() {
        val selection = LatestSelection()
        assertNull(selection.snapshot.value)
        assertEquals(SelectionScope.Unread, selection.scopeUnder(GalleryAccess.LIMITED))
        assertEquals(SelectionScope.Unrestricted, selection.scopeUnder(GalleryAccess.GRANTED))
        assertIs<Fact.Failed>(selection.photosUnder(GalleryAccess.LIMITED))
        assertEquals<Fact<Int>>(Fact.Unsupported, selection.photosUnder(GalleryAccess.GRANTED))
    }

    @Test
    fun `each emission becomes the snapshot before its consumer runs and following ends with the channel`() = runTest {
        val selection = LatestSelection()
        val changes = Channel<SelectionSnapshot>(Channel.UNLIMITED)
        val seen = mutableListOf<List<AssetId>?>()
        changes.send(SelectionSnapshot(listOf(photo("A"), photo("B"))))
        changes.send(SelectionSnapshot(listOf(photo("C"))))
        changes.close()

        selection.follow(changes) { seen += selection.snapshot.value?.map { it.assetId } }

        assertEquals<List<List<AssetId>?>>(listOf(listOf(AssetId("A"), AssetId("B")), listOf(AssetId("C"))), seen)
        val scoped = assertIs<SelectionScope.Scoped>(selection.scopeUnder(GalleryAccess.LIMITED))
        assertEquals(listOf(AssetId("C")), scoped.resources.map { it.assetId })
        assertEquals<Fact<Int>>(Fact.Known(1), selection.photosUnder(GalleryAccess.LIMITED))
    }

    @Test
    fun `a full grant is answerable at once`() = runTest {
        LatestSelection().awaitAnswerable(GalleryAccess.GRANTED)
    }

    @Test
    fun `a partial grant is answerable only once the first snapshot arrives`() = runTest {
        val selection = LatestSelection()
        val changes = Channel<SelectionSnapshot>(Channel.UNLIMITED)
        var answerable = false
        launch {
            selection.awaitAnswerable(GalleryAccess.LIMITED)
            answerable = true
        }
        runCurrent()
        assertFalse(answerable, "the selection has not been read yet")

        changes.send(SelectionSnapshot(emptyList()))
        changes.close()
        selection.follow(changes) { }
        runCurrent()
        assertTrue(answerable, "an empty selection is a read one")
    }
}
