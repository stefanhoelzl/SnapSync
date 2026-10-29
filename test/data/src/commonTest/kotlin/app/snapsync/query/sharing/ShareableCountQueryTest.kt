package app.snapsync.query.sharing

import app.snapsync.data.sharing.DURING
import app.snapsync.data.sharing.Library
import app.snapsync.data.sharing.range
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shareable-count preview: the count the upload's own admission states, or no row where none can be stated. */
class ShareableCountQueryTest {

    @Test
    fun `a readable library is counted, and an unreadable one has no count - never a zero`() = runTest {
        val readable = Library(LibraryAssets.photo("A", DURING), LibraryAssets.screenshot("S", DURING))
        assertEquals(1, ShareableCountQuery(readable.own).count(range(), received = emptySet()))

        val unread = Library(LibraryAssets.photo("A", DURING), grant = LibraryGrant.LIMITED)
        assertNull(ShareableCountQuery(unread.own).count(range(), received = emptySet()))
    }
}
