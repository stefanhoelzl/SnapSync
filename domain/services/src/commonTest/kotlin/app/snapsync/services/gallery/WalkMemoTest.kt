package app.snapsync.services.gallery



import app.snapsync.model.AssetId
import app.snapsync.model.GalleryAccess
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.candidatesFromResources
import app.snapsync.model.captureCutoff
import app.snapsync.model.selectionRulesFor
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.PhotoGrantRead
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The app process's walk memo (capability `photo-sharing`, "An unchanged library is answered from the walk memo";
 * decision record `changes/own-work-per-wake`, D9): an unchanged library under the same policy and a full grant is
 * answered without a walk, with exactly what the walk returned; every other key walks afresh; and nothing that was
 * not an authoritative, completed, full-grant walk is ever stored.
 */
class WalkMemoTest {

    /**
     * A library as the platform presents it to the memo: a walk over [assets] (counted), and a change token that
     * moves on every change — a fresh token object per read, equal by value, as PhotoKit's are.
     */
    private class Library(var assets: List<String>) : UploadDiscovery {
        var grant = GalleryAccess.GRANTED
        var readable = true
        var walks = 0
        var tokenReads = 0
        var tokenAvailable = true

        /** Runs inside the next walk, after the memo read the token — a change landing mid-walk. */
        var duringWalk: (() -> Unit)? = null
        private var version = 0

        fun change(to: List<String>) {
            assets = to
            version++
        }

        override suspend fun discover(policy: SelectionPolicy): Discovery {
            walks++
            duringWalk?.also { duringWalk = null }?.invoke()
            if (!readable) return Discovery(emptyList(), fullEnumeration = false)
            return Discovery(candidatesFromResources(assets.map(::resource)), fullEnumeration = grant == GalleryAccess.GRANTED)
        }

        override suspend fun resourcesFor(keys: Set<String>): List<Resource> =
            assets.map(::resource).filter { it.filename in keys }

        /** The library's change token, as the platform reads it. */
        val tokens = object : LibraryChangeTokenRead {
            override suspend fun changeToken(): LibraryChangeToken? {
                tokenReads++
                return if (tokenAvailable) Token(version) else null
            }
        }

        /** The process's grant, as the platform reads it. */
        val grantRead = PhotoGrantRead { grant }

        private class Token(val version: Int) : LibraryChangeToken {
            override fun sameLibraryAs(other: LibraryChangeToken) = other is Token && other.version == version
        }
    }

    private class Recorder : LogWriter() {
        val lines = mutableListOf<Pair<Severity, String>>()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += severity to message
        }
        fun logger() = Logger(loggerConfigInit(this), "WalkMemoTest")
    }

    private fun memo(library: Library, use: WalkMemoUse = WalkMemoUse.SERVE, log: Recorder = Recorder()) =
        WalkMemo(library, library.tokens, library.grantRead, use, log.logger())

    private fun ids(discovery: Discovery) = discovery.candidates.map { it.facts.assetId }

    @Test
    fun `an unchanged library is answered from the memo exactly as the walk answered`() = runTest {
        val library = Library(listOf("A", "B"))
        val memo = memo(library)

        val first = memo.discover(policy("2026-01-01T00:00:00Z"))
        val second = memo.discover(policy("2026-01-01T00:00:00Z"))

        assertEquals(1, library.walks, "the second walk is answered without enumerating the library")
        assertSame(first, second, "the memo serves what the walk returned: the same candidates")
        assertTrue(second.fullEnumeration, "and the same authority, so it deletes as a fresh walk would")
    }

    @Test
    fun `a library change walks afresh and replaces the entry`() = runTest {
        val library = Library(listOf("A", "B"))
        val memo = memo(library)
        memo.discover(policy("2026-01-01T00:00:00Z"))

        library.change(listOf("A"))
        val after = memo.discover(policy("2026-01-01T00:00:00Z"))
        val again = memo.discover(policy("2026-01-01T00:00:00Z"))

        assertEquals(listOf(AssetId("A")), ids(after), "B left the library, and the fresh walk says so")
        assertEquals(2, library.walks, "the changed token walked once, and the replaced entry served the next")
        assertSame(after, again)
    }

    @Test
    fun `a changed policy walks afresh`() = runTest {
        val library = Library(listOf("A"))
        val memo = memo(library)
        memo.discover(policy("2026-01-01T00:00:00Z"))

        memo.discover(policy("2026-02-01T00:00:00Z"))

        assertEquals(2, library.walks, "a walk made under another cutoff is never reused")
    }

    @Test
    fun `a walk under another grant is passed through and reads no token`() = runTest {
        val library = Library(listOf("A"))
        val memo = memo(library)
        memo.discover(policy("2026-01-01T00:00:00Z"))

        library.grant = GalleryAccess.LIMITED
        val limited = memo.discover(policy("2026-01-01T00:00:00Z"))

        assertEquals(2, library.walks, "the full grant's entry is not served under a partial one")
        assertFalse(limited.fullEnumeration, "the partial grant's own answer crosses unchanged")
        assertEquals(1, library.tokenReads, "a partial grant never meets the memo, not even its token read")
    }

    @Test
    fun `a limited-grant walk is never memoised`() = runTest {
        val library = Library(listOf("A"))
        library.grant = GalleryAccess.LIMITED
        val memo = memo(library)
        memo.discover(policy("2026-01-01T00:00:00Z"))

        library.grant = GalleryAccess.GRANTED
        val full = memo.discover(policy("2026-01-01T00:00:00Z"))

        assertEquals(2, library.walks, "nothing the partial grant saw is served once the grant is full")
        assertTrue(full.fullEnumeration)
    }

    @Test
    fun `an unreadable walk is never memoised`() = runTest {
        val library = Library(listOf("A"))
        library.readable = false
        val memo = memo(library)
        memo.discover(policy("2026-01-01T00:00:00Z"))

        library.readable = true
        val readable = memo.discover(policy("2026-01-01T00:00:00Z"))

        assertEquals(2, library.walks, "an unreadable read is no answer to reuse")
        assertEquals(listOf(AssetId("A")), ids(readable))
    }

    @Test
    fun `an abandoned walk stores nothing`() = runTest {
        val library = Library(listOf("A"))
        library.duringWalk = { throw CancellationException("the OS signalled that time is up") }
        val memo = memo(library)
        assertFailsWith<CancellationException> { memo.discover(policy("2026-01-01T00:00:00Z")) }

        memo.discover(policy("2026-01-01T00:00:00Z"))

        assertEquals(2, library.walks, "a walk stopped before it completed is not authoritative, so it is not stored")
    }

    @Test
    fun `a change during the walk is not hidden`() = runTest {
        val library = Library(listOf("A"))
        library.duringWalk = { library.change(listOf("A", "B")) }
        val memo = memo(library)
        memo.discover(policy("2026-01-01T00:00:00Z"))

        val next = memo.discover(policy("2026-01-01T00:00:00Z"))

        assertEquals(2, library.walks, "the entry carries the token read BEFORE the walk, so the next read differs")
        assertEquals(listOf(AssetId("A"), AssetId("B")), ids(next))
    }

    @Test
    fun `no token is no memo`() = runTest {
        val library = Library(listOf("A"))
        library.tokenAvailable = false
        val memo = memo(library)

        memo.discover(policy("2026-01-01T00:00:00Z"))
        memo.discover(policy("2026-01-01T00:00:00Z"))

        assertEquals(2, library.walks, "a platform that gives no token cannot tell 'unchanged', so every walk walks")
    }

    @Test
    fun `a new memo holds nothing`() = runTest {
        val library = Library(listOf("A"))
        memo(library).discover(policy("2026-01-01T00:00:00Z"))

        memo(library).discover(policy("2026-01-01T00:00:00Z"))

        assertEquals(2, library.walks, "in memory only: a new process's first walk enumerates")
    }

    @Test
    fun `shadow never serves but says whether serving would have been right`() = runTest {
        val library = Library(listOf("A"))
        val log = Recorder()
        val memo = memo(library, WalkMemoUse.SHADOW, log)

        memo.discover(policy("2026-01-01T00:00:00Z"))
        memo.discover(policy("2026-01-01T00:00:00Z"))
        assertEquals(2, library.walks, "shadow walks every time")
        assertTrue(log.lines.none { it.first == Severity.Error }, "an unchanged library agreed: ${log.lines}")

        // A token that did not move although the library did — the case the external-change check exists for.
        library.assets = listOf("A", "B")
        memo.discover(policy("2026-01-01T00:00:00Z"))

        assertEquals(3, library.walks)
        assertTrue(
            log.lines.any { (severity, line) -> severity == Severity.Error && "1 added" in line },
            "a stale token is reported at Error, which crash reporting sees: ${log.lines}",
        )
    }

    @Test
    fun `the resolve is never memoised`() = runTest {
        val library = Library(listOf("A"))
        val memo = memo(library)

        val first = memo.resourcesFor(setOf("A-primary.jpg"))
        library.change(emptyList())
        val second = memo.resourcesFor(setOf("A-primary.jpg"))

        assertEquals(listOf("A-primary.jpg"), first.map { it.filename })
        assertTrue(second.isEmpty(), "an id-scoped read is always the platform's current answer")
    }

    private companion object {
        fun resource(assetId: String) =
            Resource(filename = "$assetId-primary.jpg", assetId = AssetId(assetId), contentType = "image/jpeg", metadata = emptyMap(), data = Unit)

        suspend fun policy(cutoff: String) = SelectionPolicy(
            selectionRulesFor(
                includesUpload = true,
                cutoff = captureCutoff(cutoff),
                ceiling = null,
                suppressedAssetIds = { emptySet() },
                albumExcludedAssetIds = { emptySet() },
            ),
        )
    }
}
