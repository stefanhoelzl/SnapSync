package app.snapsync.status

import app.snapsync.data.sharing.Contribution
import app.snapsync.data.sharing.DenylistedAlbums
import app.snapsync.data.sharing.GalleryWalk
import app.snapsync.data.sharing.LibraryWalk
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.sharing.WalkRead
import app.snapsync.data.time.WallClock
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.WalkBounds
import app.snapsync.domain.time.Timestamp
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryGallery
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.SelectionSnapshot
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

private fun at(text: String) = Timestamp.of(Instant.parse(text))

/**
 * Every membership carries a range (capability `photo-sharing`); there is no whole-library total. The member's range is
 * the event's own week, and the event has started, so it is shared whole.
 */
private val RANGE: CaptureRange = (
    CaptureRange.of(
        CaptureCutoff(at("2026-07-06T00:00:00Z")),
        CaptureCeiling(at("2026-07-13T00:00:00Z")),
        EventStart(at("2026-07-06T00:00:00Z")),
        EventEnd(at("2026-07-13T00:00:00Z")),
    ) as CaptureRangeVerdict.Ok
    ).range

/** Inside [RANGE], so a default-dated photo is in scope. */
private const val IN_SCOPE = "2026-07-10T00:00:00Z"

class OwnDeviceGalleryStatusSourceTest {

    private val clock = WallClock(fixedClock(Instant.parse("2026-07-20T00:00:00Z")))

    /** The in-memory library the member's photos are read from, counting the walks that reach the gallery. */
    private class Library(
        val cell: MutableStateFlow<List<GalleryAssetResources>>,
        access: LibraryGrant = LibraryGrant.GRANTED,
        albums: Map<String, Set<PlatformAssetId>> = emptyMap(),
        private val reader: GalleryReader = inMemoryGallery(cell, MutableStateFlow(access), MutableStateFlow(albums)),
    ) : GalleryReader by reader {
        var walks = 0
        override suspend fun assets(filter: AssetFilter): GalleryRead<List<GalleryAsset>> =
            reader.assets(filter).also { walks++ }
    }

    /** A walk whose behaviour can be swapped mid-test, so one source can succeed and then fail. */
    private class Switchable(private val real: LibraryWalk) : LibraryWalk {
        var mode: Mode = Mode.READS

        enum class Mode { READS, BLOWS_UP, NOT_READABLE, CANCELLED }

        override suspend fun read(bounds: WalkBounds): WalkRead = when (mode) {
            Mode.READS -> real.read(bounds)
            Mode.BLOWS_UP -> error("the library walk blew up")
            Mode.NOT_READABLE -> WalkRead.NotReadable
            Mode.CANCELLED -> throw CancellationException("the scope was torn down")
        }
    }

    private class Member(val library: Library, val walk: Switchable, val photos: OwnPhotos) {
        val source = OwnDeviceGalleryStatusSource(photos)

        /** What a member of [direction] sharing [RANGE] contributes, having received [received]. */
        suspend fun contribution(
            direction: Direction = Direction.Both,
            received: Set<AssetId> = emptySet(),
        ): Contribution = photos.contribution(direction, RANGE, received)

        suspend fun refresh(direction: Direction = Direction.Both, received: Set<AssetId> = emptySet()) =
            source.refresh(contribution(direction, received))
    }

    private fun member(library: Library, selection: SelectionSnapshots = SelectionSnapshots()): Member {
        val walk = Switchable(GalleryWalk(library, clock))
        return Member(library, walk, OwnPhotos(library, walk, selection, DenylistedAlbums(library), clock))
    }

    private fun member(vararg photos: GalleryAssetResources) = member(Library(MutableStateFlow(photos.toList())))

    private fun photo(id: String, date: String = IN_SCOPE) = LibraryAssets.photo(id, date)

    // ---- Not counted is not zero (capability `sync-status`) --------------------------------------

    @Test
    fun `a source that has never been refreshed reports not counted`() = runTest {
        val member = member(photo("A"))

        // `null`, NOT `0`. The status projection settles to "In sync" once the synced count reaches the
        // total, so a seeded zero here rendered a check mark reading "everything shared" on a device
        // that had not looked (`SNAPSYNC-14`, `SNAPSYNC-16`).
        assertNull(member.source.admitted.value, "an un-refreshed total is not a count of zero")
    }

    @Test
    fun `a failed enumeration leaves the total un-counted rather than zero`() = runTest {
        val member = member(photo("A"))
        member.walk.mode = Switchable.Mode.BLOWS_UP

        // Does NOT propagate: the invariant is this source's, so the containment is too, and a caller
        // no longer has to remember to protect a rule it does not own.
        member.refresh()

        // The distinction the law demands: "could not count" must not collapse into "counted nothing",
        // because the second settles the screen and the first must not.
        assertNull(member.source.admitted.value, "a failed walk publishes no count")
    }

    @Test
    fun `a failed enumeration retains the last good count`() = runTest {
        val member = member(photo("A"))

        member.refresh()
        assertEquals(setOf(testAssetId("A")), member.source.admitted.value)

        member.walk.mode = Switchable.Mode.BLOWS_UP
        member.refresh()

        // Not regressed to `null` either: a transient walk failure must not un-count a total that WAS
        // counted, or the screen drops out of "In sync" on a device that has changed nothing — the same
        // rule `ReadingLedgerCountsSource` keeps for the ledger counts beside it.
        assertEquals(
            setOf(testAssetId("A")),
            member.source.admitted.value,
            "a failed walk leaves the previous count standing",
        )
    }

    @Test
    fun `refresh publishes the admitted ids rather than just their count`() = runTest {
        // Status counts the ledger's per-photo done-ness over exactly THESE ids (capability `sync-status`),
        // so a count alone is not enough: two libraries of the same size must publish different sets.
        val member = member(photo("A"), photo("B"))

        member.refresh()
        assertEquals(setOf(testAssetId("A"), testAssetId("B")), member.source.admitted.value)

        member.library.cell.value = listOf(photo("C"), photo("D"))
        member.refresh()

        // Same N, different photos — a size-only projection could not tell these apart.
        assertEquals(setOf(testAssetId("C"), testAssetId("D")), member.source.admitted.value)
    }

    @Test
    fun `a failed refresh keeps the previous admitted set not a re-read of the changed library`() = runTest {
        val member = member(photo("A"), photo("B"))
        member.refresh()
        assertEquals(setOf(testAssetId("A"), testAssetId("B")), member.source.admitted.value)

        // The library moved on, but the next walk failed: the set stands as last counted, ids and all.
        member.library.cell.value = listOf(photo("C"))
        member.walk.mode = Switchable.Mode.BLOWS_UP
        member.refresh()
        assertEquals(
            setOf(testAssetId("A"), testAssetId("B")),
            member.source.admitted.value,
            "a failed walk withdraws no id it counted",
        )

        // And an unreadable library keeps it just the same.
        member.walk.mode = Switchable.Mode.NOT_READABLE
        member.refresh()
        assertEquals(
            setOf(testAssetId("A"), testAssetId("B")),
            member.source.admitted.value,
            "a refusal withdraws no id it counted",
        )
    }

    @Test
    fun `cancellation is rethrown rather than logged as a failed walk`() = runTest {
        val member = member(photo("A"))
        val contribution = member.contribution()
        member.walk.mode = Switchable.Mode.CANCELLED

        // Swallowing this would break structured concurrency and post an Error-severity line — which
        // reaches the crash reporter on production builds — for an ordinary teardown.
        assertFailsWith<CancellationException> { member.source.refresh(contribution) }
    }

    @Test
    fun `an unreadable library publishes no count and withdraws none`() = runTest {
        val member = member(photo("A"))

        // Never counted, and unreadable: still `null`. Not `0` — a zero here settles the screen at
        // "In sync" on a device that has not looked (`SNAPSYNC-14`, `SNAPSYNC-16`).
        member.walk.mode = Switchable.Mode.NOT_READABLE
        member.refresh()
        assertNull(member.source.admitted.value, "an unreadable library publishes no count")

        // Counted, THEN unreadable: the count stands. One rule covers this and the thrown walk above —
        // never publish a count we did not compute, never withdraw one we did. A refusal must not be
        // more destructive than a failure, which `sync-status` already requires to leave the previous
        // value in place.
        member.walk.mode = Switchable.Mode.READS
        member.refresh()
        assertEquals(setOf(testAssetId("A")), member.source.admitted.value)

        member.walk.mode = Switchable.Mode.NOT_READABLE
        member.refresh()
        assertEquals(
            setOf(testAssetId("A")),
            member.source.admitted.value,
            "a refusal withdraws no count it did not take",
        )
    }

    @Test
    fun `a refused grant publishes no count and withdraws none`() = runTest {
        // The same rule reached through the grant rather than a failed walk: no grant leaves nothing readable.
        val access = MutableStateFlow(LibraryGrant.DENIED)
        val cell = MutableStateFlow(listOf(photo("A")))
        val member = member(Library(cell, reader = inMemoryGallery(cell, access)))

        member.refresh()
        assertNull(member.source.admitted.value, "a refused grant publishes no count")

        access.value = LibraryGrant.GRANTED
        member.refresh()
        access.value = LibraryGrant.DENIED
        member.refresh()
        assertEquals(setOf(testAssetId("A")), member.source.admitted.value, "a refusal withdraws no count")
    }

    @Test
    fun `a counted zero is distinguishable from never counted`() = runTest {
        val member = member()
        assertNull(member.source.admitted.value)

        member.refresh()
        assertEquals(
            emptySet<AssetId>(),
            member.source.admitted.value,
            "an empty library that WAS counted reports a real zero",
        )
    }

    @Test
    fun `a non-contributing membership counts a real zero rather than the un-counted seed`() = runTest {
        val member = member(photo("A"))
        assertNull(member.source.admitted.value)

        // A direction without upload admits nothing, so the count reaches 0 through the ordinary admission. It is a
        // COUNTED zero and must settle the screen exactly as a completed count does — the fix must not
        // turn a legitimate "nothing to share" into a permanent neutral line.
        member.refresh(Direction.DownloadOnly)

        assertEquals(emptySet<AssetId>(), member.source.admitted.value)
    }

    @Test
    fun `a non-contributing membership totals zero without walking the library`() = runTest {
        for (direction in listOf(Direction.DownloadOnly, Direction.Neither)) {
            val member = member(photo("A"), photo("B"))

            member.refresh(direction)

            assertEquals(
                emptySet<AssetId>(),
                member.source.admitted.value,
                "$direction: a member who shares nothing has nothing to count",
            )
            // The load-bearing half. Counting 0 by walking 4000 assets would be ~7 minutes of PhotoKit XPC to learn
            // what the direction already said — so a policy that admits nothing reads nothing (capability
            // `sync-status`).
            assertEquals(0, member.library.walks, "$direction: the library is never walked")
        }
    }

    @Test
    fun `a contributing membership still walks and counts`() = runTest {
        // The control: a non-contributing direction is not a blanket off-switch, it is one branch.
        val member = member(photo("A"), photo("B"))

        member.refresh()

        assertEquals(setOf(testAssetId("A"), testAssetId("B")), member.source.admitted.value)
        assertEquals(1, member.library.walks)
    }

    // ---- the selection snapshot serves the total (capability `photo-access`) ----
    // There is no `refreshFrom`: under LIMITED the selection is the gallery, so the total has ONE entry point regardless
    // of grant. A second one restated the mode difference the read owns, and it is that restatement — not the
    // reading — that lets two paths drift apart.

    @Test
    fun `a snapshot-backed source is counted through the same admission`() = runTest {
        val cell = MutableStateFlow(listOf(photo("A"), photo("B", "2026-07-01T00:00:00Z"), photo("NOT-SELECTED")))
        val selection = SelectionSnapshots()
        selection.published(flowOf(SelectionSnapshot(cell.value.take(2)))) {}.collect()
        val member = member(Library(cell, access = LibraryGrant.LIMITED), selection)

        member.refresh()

        assertEquals(
            setOf(testAssetId("A")),
            member.source.admitted.value,
            "the snapshot is counted through the same admission: B is before the range",
        )
        assertEquals(0, member.library.walks, "under a partial grant the selection is the gallery")
    }

    @Test
    fun `a partial grant whose selection is not yet read publishes no count`() = runTest {
        val member = member(Library(MutableStateFlow(listOf(photo("A"))), access = LibraryGrant.LIMITED))

        member.refresh()

        assertNull(member.source.admitted.value, "an unread selection is not an empty one")
    }

    // ---- the total counts what the cycle admits (capability `photo-sharing`) ----

    @Test
    fun `an origin-excluded asset does not inflate the total`() = runTest {
        // The status source enumerates INDEPENDENTLY of the upload cycle, so it must apply the identical
        // policy. If it counted the screenshot the cycle refuses to upload, N would be 3 while only 2 could
        // ever complete — and the joined screen would sit at "pending" forever. That is the whole reason
        // this rule is a requirement rather than an implementation detail.
        val member = member(
            photo("CAM"),
            LibraryAssets.screenshot("SHOT", IN_SCOPE),
            LibraryAssets.lowResPhoto("WA", IN_SCOPE), // below the floor
        )

        member.refresh()

        assertEquals(setOf(testAssetId("CAM")), member.source.admitted.value, "only the camera photo counts toward N")
    }

    @Test
    fun `a denylisted album member does not inflate the total`() = runTest {
        val member = member(
            Library(
                MutableStateFlow(listOf(photo("CAM"), photo("WA"))),
                albums = mapOf("WhatsApp" to setOf(PlatformAssetId("WA"))),
            ),
        )

        member.refresh()

        assertEquals(setOf(testAssetId("CAM")), member.source.admitted.value)
    }

    @Test
    fun `the admitted set counts own qualifying assets by photo`() = runTest {
        // A is a Live Photo: two resources, one photo.
        val member = member(LibraryAssets.livePhoto("A", IN_SCOPE), photo("B"))

        member.refresh()

        // A and B — counted by photo, not resource row
        assertEquals(setOf(testAssetId("A"), testAssetId("B")), member.source.admitted.value)
    }

    @Test
    fun `downloaded suppressed assets are excluded from the upload total`() = runTest {
        // B is a foreign photo this device downloaded + imported (suppressed). It is in the library
        // (enumerated) but must NOT count toward the upload universe — else progress pegs below 100%.
        val member = member(photo("A"), photo("B"))

        member.refresh(received = setOf(testAssetId("B")))

        assertEquals(
            setOf(testAssetId("A")),
            member.source.admitted.value,
            "total counts only own assets (A), not the downloaded B",
        )
    }

    @Test
    fun `refresh recomputes after the library changes`() = runTest {
        val member = member(photo("A"))
        member.refresh()
        assertEquals(setOf(testAssetId("A")), member.source.admitted.value)

        member.library.cell.value = listOf(photo("A"), photo("C"))
        member.refresh()
        assertEquals(setOf(testAssetId("A"), testAssetId("C")), member.source.admitted.value)
    }

    @Test
    fun `pre-cutoff assets are excluded from the total so progress can reach 100 percent`() = runTest {
        // OLD precedes the range → never uploads → must not inflate N (else the screen shows "pending"
        // forever). NEW is inside it → counted (capability `photo-sharing`).
        val member = member(photo("OLD", "2026-07-01T00:00:00Z"), photo("NEW", "2026-07-10T00:00:00Z"))

        member.refresh()

        assertEquals(
            setOf(testAssetId("NEW")),
            member.source.admitted.value,
            "only the in-range asset (NEW) counts toward the total",
        )
    }

    @Test
    fun `post-ceiling assets are excluded from the total`() = runTest {
        // The defect the shared admission exists for: a ceiling that reached the upload but not the total.
        val member = member(photo("IN"), photo("AFTER", "2026-07-15T00:00:00Z"))

        member.refresh()

        assertEquals(setOf(testAssetId("IN")), member.source.admitted.value)
    }

    @Test
    fun `an undated asset is excluded under a range`() = runTest {
        // A capture date that does not parse is a photo the platform holds no date for.
        val member = member(photo("U", ""))

        member.refresh()

        assertEquals(
            emptySet<AssetId>(),
            member.source.admitted.value,
            "an asset with no capture date is out of scope under a range",
        )
    }
}
