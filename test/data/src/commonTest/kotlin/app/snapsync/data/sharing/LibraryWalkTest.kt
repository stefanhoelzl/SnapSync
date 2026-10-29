@file:OptIn(AppProcessOnly::class)

package app.snapsync.data.sharing

import app.snapsync.data.gallery.facts
import app.snapsync.domain.sharing.WalkBounds
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Instant

/**
 * A walk of the member's library within the policy's capture dates: one facts read narrowed to the bounds, resources
 * deferred to one request by id — and the app's walk memo, which answers an unchanged library under the same bounds
 * from its last successful walk (decision record `changes/own-work-per-wake`, D9).
 */
class LibraryWalkTest {

    // ---- GalleryWalk ------------------------------------------------------------------------------------------------

    @Test
    fun `no bounds read nothing and are a walked empty read`() = runTest {
        val library = Library(LibraryAssets.photo("A", DURING))
        val read = assertIs<WalkRead.Walked>(GalleryWalk(library.reader, library.wallClock).read(WalkBounds.None))

        assertEquals(emptyList(), read.assets)
        assertEquals(at(NOW), read.takenAt)
        assertEquals(emptyList(), library.calls, "a policy that admits nothing asks the gallery nothing")
        assertEquals(listOf("A"), read.resources.of(ids("A").toSet()).resolvedIds(), "its resources are the gallery's")
    }

    @Test
    fun `a walk asks for the bounds' capture dates and defers the resources to one request by id`() = runTest {
        val library =
            Library(LibraryAssets.photo("A", DURING), LibraryAssets.photo("B", DURING), LibraryAssets.photo("U", "?"))
        val read = assertIs<WalkRead.Walked>(GalleryWalk(library.reader, library.wallClock).read(within()))

        assertEquals(ids("A", "B"), read.assets.map { it.assetId }, "the gallery's facts, translated")
        assertEquals(at(NOW), read.takenAt)
        assertEquals(
            listOf("assets(2026-07-14T18:00:00Z..2026-07-21T18:00:00Z)"),
            library.calls,
            "facts cost no resource",
        )

        assertEquals(listOf("B"), read.resources.of(ids("B", "GONE").toSet()).resolvedIds())
        assertEquals("resources([B, GONE])", library.calls.last(), "one request, by the platform's ids")
    }

    @Test
    fun `an unreadable gallery is an unreadable walk`() = runTest {
        val library = Library(LibraryAssets.photo("A", DURING), grant = LibraryGrant.DENIED)
        assertEquals(WalkRead.NotReadable, GalleryWalk(library.reader, library.wallClock).read(within()))
    }

    // ---- MemoizedWalk -----------------------------------------------------------------------------------------------

    /** The library's change token as the platform reads it: a fresh object per read, equal while the library is. */
    private class Tokens : LibraryChangeTokenRead {
        var version = 0
        var available = true

        override suspend fun changeToken(): LibraryChangeToken? = if (available) Token(version) else null

        private class Token(val version: Int) : LibraryChangeToken {
            override fun sameLibraryAs(other: LibraryChangeToken): Boolean = other is Token && other.version == version
        }
    }

    /** A walk answering [answer], counted; [during] runs inside the next walk — a change landing mid-walk. */
    private class Scripted(var answer: WalkRead) : LibraryWalk {
        var walks = 0
        var during: (() -> Unit)? = null

        override suspend fun read(bounds: WalkBounds): WalkRead {
            walks++
            during?.also { during = null }?.invoke()
            return answer
        }
    }

    private val library = Library()
    private val tokens = Tokens()

    private fun walked(vararg id: String) = WalkRead.Walked(
        id.map { LibraryAssets.photo(it, DURING).asset.facts() },
        at("2026-07-15T12:00:00Z"),
        SnapshotResources(emptyList()),
    )

    private fun memo(walk: LibraryWalk) = MemoizedWalk(walk, tokens, library.wallClock)

    @Test
    fun `an unchanged library under the same bounds is answered without a walk, as of now`() = runTest {
        val walk = Scripted(walked("A", "B"))
        val memo = memo(walk)
        val first = assertIs<WalkRead.Walked>(memo.read(within()))

        library.clock.now = Instant.parse("2026-07-17T00:00:00Z")
        val served = assertIs<WalkRead.Walked>(memo.read(within()))

        assertEquals(1, walk.walks, "the second read is answered from the memo")
        assertSame(first.assets, served.assets, "exactly what the walk read")
        assertSame(first.resources, served.resources)
        assertEquals(
            at("2026-07-17T00:00:00Z"),
            served.takenAt,
            "the library is unchanged since, so the read is as of now",
        )
    }

    @Test
    fun `a moved token walks again and its read replaces the entry`() = runTest {
        val walk = Scripted(walked("A", "B"))
        val memo = memo(walk)
        memo.read(within())

        tokens.version++
        walk.answer = walked("A")
        val fresh = assertIs<WalkRead.Walked>(memo.read(within()))
        val again = assertIs<WalkRead.Walked>(memo.read(within()))

        assertEquals(ids("A"), fresh.assets.map { it.assetId }, "B left, and the fresh walk says so")
        assertEquals(2, walk.walks, "the moved token walked once, and the replaced entry served the next")
        assertSame(fresh.assets, again.assets)
    }

    @Test
    fun `other bounds walk again, and no bounds are an entry of their own`() = runTest {
        val walk = Scripted(walked("A"))
        val memo = memo(walk)
        memo.read(within())
        memo.read(within(from = "2026-07-15T00:00:00Z"))
        assertEquals(2, walk.walks, "a walk made under other bounds is never reused")

        memo.read(WalkBounds.None)
        memo.read(WalkBounds.None)
        assertEquals(3, walk.walks)
    }

    @Test
    fun `no token is no memo - every read walks, its answer passed through`() = runTest {
        tokens.available = false
        val answer = walked("A")
        val walk = Scripted(answer)
        val memo = memo(walk)

        memo.read(within())
        assertSame(answer, memo.read(within()))
        assertEquals(2, walk.walks, "a platform that gives no token cannot tell 'unchanged'")
    }

    @Test
    fun `an unreadable walk is never stored, and an older entry survives it`() = runTest {
        val walk = Scripted(WalkRead.NotReadable)
        val memo = memo(walk)
        assertEquals(WalkRead.NotReadable, memo.read(within()))

        walk.answer = walked("A")
        val stored = assertIs<WalkRead.Walked>(memo.read(within()))
        assertEquals(2, walk.walks, "an unreadable read is no answer to reuse")

        walk.answer = WalkRead.NotReadable
        assertEquals(WalkRead.NotReadable, memo.read(WalkBounds.None))
        val served = assertIs<WalkRead.Walked>(memo.read(within()))

        assertEquals(3, walk.walks, "the unreadable walk under other bounds left the entry in place")
        assertSame(stored.assets, served.assets)
    }

    @Test
    fun `the token is read before the walk, so a change during it is not hidden`() = runTest {
        val walk = Scripted(walked("A"))
        walk.during = { tokens.version++ }
        val memo = memo(walk)
        memo.read(within())

        memo.read(within())

        assertEquals(2, walk.walks, "the entry carries the token read BEFORE the walk, so the next read differs")
    }
}
