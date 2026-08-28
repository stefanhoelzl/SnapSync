package app.snapsync.status

import app.snapsync.model.candidatesFromResources
import app.snapsync.model.Candidate
import app.snapsync.model.RESOURCE_META_IS_SCREENSHOT
import app.snapsync.model.RESOURCE_META_IS_VIDEO
import app.snapsync.model.RESOURCE_META_PIXEL_AREA
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionRulesFor
import app.snapsync.model.SelectionRule
import app.snapsync.model.captureCutoff
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.model.CandidateRead
import app.snapsync.ports.CandidateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** Every membership carries a cutoff (capability `photo-selection-policy`); there is no whole-library total. */
private val CUTOFF = captureCutoff("2026-07-06T00:00:00Z")

/**
 * The admitting policy every test here drives, bounded below by [CUTOFF] and unbounded above.
 *
 * A `suspend fun` rather than a `val`: the one derivation reads two ports (capability
 * `photo-selection-policy`). The two exclusion sets are parameters because they used to be injected into
 * this status source and applied by it — the source now receives a finished policy and applies nothing.
 */
private suspend fun admitting(
    echo: Set<String> = emptySet(),
    albumExcluded: Set<String> = emptySet(),
): SelectionPolicy = SelectionPolicy(
    selectionRulesFor(
        includesUpload = true,
        cutoff = CUTOFF,
        ceiling = null,
        suppressedAssetIds = { echo },
        albumExcludedAssetIds = { albumExcluded },
    ),
)

/** After [CUTOFF], so a default-dated resource is in scope. */
private const val IN_SCOPE = "2026-07-10T00:00:00Z"

/** A candidate source over a fixed resource list — held candidates, as a snapshot or a fake walk gives. */
private class ResourceCandidates(private val cell: MutableStateFlow<List<Resource>>) : CandidateSource {
    constructor(resources: List<Resource>) : this(MutableStateFlow(resources))

    override suspend fun candidates(policy: SelectionPolicy): CandidateRead =
        CandidateRead.Readable(candidatesFromResources(cell.value))
}

class OwnDeviceGalleryStatusSourceTest {

    /** A walk that always blows up, standing in for a platform enumeration that failed. */
    private class Blowing : CandidateSource {
        override suspend fun candidates(policy: SelectionPolicy): CandidateRead =
            error("the library walk blew up")
    }

    /** A walk whose behaviour can be swapped mid-test, so one source can succeed and then fail. */
    private class Switchable(var delegate: CandidateSource) : CandidateSource {
        override suspend fun candidates(policy: SelectionPolicy): CandidateRead =
            delegate.candidates(policy)
    }

    /** Records whether the walk happened at all — "counted 0" and "never looked" are different claims. */
    private class RecordingEnumerator(private val delegate: CandidateSource) : CandidateSource {
        var walks = 0
        override suspend fun candidates(policy: SelectionPolicy): CandidateRead {
            walks++
            return delegate.candidates(policy)
        }
    }

    // ---- The direction gate, for the total (capability `photo-selection-policy`) ----------------------
    // N must count "the same set the upload cycle admits" — the invariant this class states about itself.
    // The cutoff and origin exclusions were honoured on both sides; the participation direction on neither.
    // Unlike the download arm's total (which flows THROUGH its gate and is zero for free), N is a parallel
    // computation no upload gate feeds — so the short-circuit has to be right here or not at all.

    // ---- Not counted is not zero (capability `gallery-status`) --------------------------------------

    @Test
    fun `a source that has never been refreshed reports not counted`() = runTest {
        val source = OwnDeviceGalleryStatusSource(
            ResourceCandidates(listOf(resource("A-primary.jpg", "A"))),
        )

        // `null`, NOT `0`. The status projection settles to "In sync" once the synced count reaches the
        // total, so a seeded zero here rendered a check mark reading "everything shared" on a device
        // that had not looked (`SNAPSYNC-14`, `SNAPSYNC-16`).
        assertNull(source.size.value, "an un-refreshed total is not a count of zero")
    }

    @Test
    fun `a failed enumeration leaves the total un-counted rather than zero`() = runTest {
        val source = OwnDeviceGalleryStatusSource(Blowing())

        // Does NOT propagate: the invariant is this source's, so the containment is too, and a caller
        // no longer has to remember to protect a rule it does not own.
        source.refresh(admitting())

        // The distinction the law demands: "could not count" must not collapse into "counted nothing",
        // because the second settles the screen and the first must not.
        assertNull(source.size.value, "a failed walk publishes no count")
    }

    @Test
    fun `a failed enumeration retains the last good count`() = runTest {
        val walk = Switchable(ResourceCandidates(listOf(resource("A-primary.jpg", "A"))))
        val source = OwnDeviceGalleryStatusSource(walk)

        source.refresh(admitting())
        assertEquals(1, source.size.value)

        walk.delegate = Blowing()
        source.refresh(admitting())

        // Not regressed to `null` either: a transient walk failure must not un-count a total that WAS
        // counted, or the screen drops out of "In sync" on a device that has changed nothing — the same
        // rule `ReadingLedgerCountsSource` keeps for the ledger counts beside it.
        assertEquals(1, source.size.value, "a failed walk leaves the previous count standing")
    }

    @Test
    fun `cancellation is rethrown rather than logged as a failed walk`() = runTest {
        val source = OwnDeviceGalleryStatusSource(
            object : CandidateSource {
                override suspend fun candidates(policy: SelectionPolicy): CandidateRead =
                    throw CancellationException("the scope was torn down")
            },
        )

        // Swallowing this would break structured concurrency and post an Error-severity line — which
        // reaches the crash reporter on production builds — for an ordinary teardown.
        assertFailsWith<CancellationException> { source.refresh(admitting()) }
    }

    @Test
    fun `an unreadable library publishes no count and withdraws none`() = runTest {
        val cell = MutableStateFlow(listOf(resource("A-primary.jpg", "A")))
        var readable = true
        val source = OwnDeviceGalleryStatusSource(
            object : CandidateSource {
                override suspend fun candidates(policy: SelectionPolicy): CandidateRead =
                    if (readable) CandidateRead.Readable(candidatesFromResources(cell.value))
                    else CandidateRead.NotReadable
            },
        )

        // Never counted, and unreadable: still `null`. Not `0` — a zero here settles the screen at
        // "In sync" on a device that has not looked (`SNAPSYNC-14`, `SNAPSYNC-16`).
        readable = false
        source.refresh(admitting())
        assertNull(source.size.value, "an unreadable library publishes no count")

        // Counted, THEN unreadable: the count stands. One rule covers this and the thrown walk above —
        // never publish a count we did not compute, never withdraw one we did. A refusal must not be
        // more destructive than a failure, which `gallery-status` already requires to leave the previous
        // value in place.
        readable = true
        source.refresh(admitting())
        assertEquals(1, source.size.value)

        readable = false
        source.refresh(admitting())
        assertEquals(1, source.size.value, "a refusal withdraws no count it did not take")
    }

    @Test
    fun `a counted zero is distinguishable from never counted`() = runTest {
        val source = OwnDeviceGalleryStatusSource(ResourceCandidates(emptyList()))
        assertNull(source.size.value)

        source.refresh(admitting())
        assertEquals(0, source.size.value, "an empty library that WAS counted reports a real zero")
    }

    @Test
    fun `a non-contributing membership counts a real zero rather than the un-counted seed`() = runTest {
        val source = OwnDeviceGalleryStatusSource(
            ResourceCandidates(listOf(resource("A-primary.jpg", "A"))),
        )
        assertNull(source.size.value)

        // `DenyAll` admits nothing, so the count reaches 0 through the ordinary admission. It is a
        // COUNTED zero and must settle the screen exactly as a completed count does — the fix must not
        // turn a legitimate "nothing to share" into a permanent neutral line.
        source.refresh(SelectionPolicy(listOf(SelectionRule.DenyAll)))

        assertEquals(0, source.size.value)
    }

    @Test
    fun `a non-contributing membership totals zero without walking the library`() = runTest {
        val enumerator = RecordingEnumerator(
            ResourceCandidates(
                listOf(resource("A-primary.jpg", "A"), resource("B-primary.jpg", "B")),
            ),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(SelectionPolicy(listOf(SelectionRule.DenyAll)))

        assertEquals(0, source.size.value, "a member who shares nothing has nothing to count")
        // The load-bearing half, and where it now lives. Counting 0 by walking 4000 assets would be ~7
        // minutes of PhotoKit XPC to learn what the direction already said — so the deny-everything rule
        // is translated into a fetch predicate matching NO asset (capability `gallery-status`), and the
        // cost is removed at the fetch rather than by this source refusing to start one. Exactly one
        // fetch is issued, per refresh rather than per asset; this fake does not translate rules, so its
        // list comes back and is refused by `admits`.
        assertEquals(1, enumerator.walks, "one fetch, which a real platform narrows to nothing")
    }

    @Test
    fun `a contributing membership still walks and counts`() = runTest {
        // The control: None is not a blanket off-switch, it is one branch. Since must behave exactly as the
        // bare cutoff did before, or this change quietly broke every normal member's progress.
        val enumerator = RecordingEnumerator(
            ResourceCandidates(
                listOf(resource("A-primary.jpg", "A"), resource("B-primary.jpg", "B")),
            ),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(admitting())

        assertEquals(2, source.size.value)
        assertEquals(1, enumerator.walks)
    }

    /** Dated in scope by default: an asset with no `creationDate` is out of scope under any cutoff. */
    // ---- the selection snapshot serves the total (capability `limited-photo-access`) ----
    // There is no `refreshFrom` any more: the permission-aware source supplies the snapshot under LIMITED,
    // so the total has ONE entry point regardless of grant. A second one restated the mode difference the
    // source owns, and it is that restatement — not the reading — that lets two paths drift apart.

    @Test
    fun `a snapshot-backed source is counted through the same admission`() = runTest {
        // The snapshot backs the source instead of being pushed in as an argument; the admission over it
        // is identical either way, which is the whole point of the collapse.
        val snapshot = ResourceCandidates(
            listOf(
                resource("A-primary.jpg", "A"),
                datedResource("B-primary.jpg", "B", "2026-07-01T00:00:00Z"), // pre-cutoff → excluded
            ),
        )
        val source = OwnDeviceGalleryStatusSource(snapshot)

        source.refresh(admitting())

        assertEquals(1, source.size.value, "the snapshot is counted through the same admission")
    }

    private fun resource(filename: String, assetId: String) =
        Resource(filename, assetId, "image/jpeg", mapOf(RESOURCE_META_CREATION_DATE to IN_SCOPE), Unit)

    private fun datedResource(filename: String, assetId: String, creationDate: String) =
        Resource(filename, assetId, "image/jpeg", mapOf(RESOURCE_META_CREATION_DATE to creationDate), Unit)

    private fun undatedResource(filename: String, assetId: String) =
        Resource(filename, assetId, "image/jpeg", emptyMap(), Unit)

    /** A resource carrying the origin facts (capability `photo-selection-policy`). */
    private fun originResource(
        filename: String,
        assetId: String,
        isScreenshot: Boolean = false,
        width: Long = 4032,
        height: Long = 3024,
    ) = Resource(
        filename, assetId, "public.heic",
        mapOf(
            RESOURCE_META_CREATION_DATE to IN_SCOPE,
            RESOURCE_META_IS_SCREENSHOT to isScreenshot.toString(),
            RESOURCE_META_IS_VIDEO to "false",
            RESOURCE_META_PIXEL_AREA to (width * height).toString(),
        ),
        Unit,
    )

    @Test
    fun `an origin-excluded asset does not inflate the total`() = runTest {
        // The status source enumerates INDEPENDENTLY of the upload cycle, so it must apply the identical
        // policy. If it counted the screenshot the cycle refuses to upload, N would be 3 while only 2 could
        // ever complete — and the joined screen would sit at "pending" forever. That is the whole reason
        // this rule is a requirement rather than an implementation detail.
        val enumerator = ResourceCandidates(
            listOf(
                originResource("cam-primary.heic", "CAM"),
                originResource("shot-primary.png", "SHOT", isScreenshot = true),
                originResource("wa-primary.jpg", "WA", width = 1600, height = 1200), // 1.9 MP → below floor
            ),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(admitting())

        assertEquals(1, source.size.value, "only the camera photo counts toward N")
    }

    @Test
    fun `a denylisted album member does not inflate the total`() = runTest {
        val enumerator = ResourceCandidates(
            listOf(originResource("cam.heic", "CAM"), originResource("wa.heic", "WA")),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(admitting(albumExcluded = setOf("WA")))

        assertEquals(1, source.size.value)
    }

    @Test
    fun `size counts own qualifying assets by photo`() = runTest {
        val enumerator = ResourceCandidates(
            listOf(
                resource("A-primary.jpg", "A"),
                resource("A-live.mov", "A"), // A is a Live Photo: two resources, one photo
                resource("B-primary.jpg", "B"),
            ),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(admitting())

        assertEquals(2, source.size.value) // A and B — counted by photo, not resource row
    }

    @Test
    fun `downloaded suppressed assets are excluded from the upload total`() = runTest {
        // B is a foreign photo this device downloaded + imported (suppressed). It is in the library
        // (enumerated) but must NOT count toward the upload universe — else progress pegs below 100%.
        val enumerator = ResourceCandidates(
            listOf(
                resource("A-primary.jpg", "A"), // own
                resource("B-primary.jpg", "B"), // downloaded foreign (suppressed)
            ),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(admitting(echo = setOf("B")))

        assertEquals(1, source.size.value, "total counts only own assets (A), not the downloaded B")
    }

    @Test
    fun `refresh recomputes after the library changes`() = runTest {
        // The honest fake exposes only the port; the test owns the cell it reads (fake-honesty gate).
        val cell = MutableStateFlow(listOf(resource("A-primary.jpg", "A")))
        val enumerator = ResourceCandidates(cell)
        val source = OwnDeviceGalleryStatusSource(enumerator)
        source.refresh(admitting())
        assertEquals(1, source.size.value)

        cell.value = listOf(resource("A-primary.jpg", "A"), resource("C-primary.jpg", "C"))
        source.refresh(admitting())
        assertEquals(2, source.size.value)
    }

    @Test
    fun `pre-cutoff assets are excluded from the total so progress can reach 100 percent`() = runTest {
        // OLD precedes the cutoff → never uploads → must not inflate N (else the screen shows "pending"
        // forever). NEW is at/after the cutoff → counted (capability photo-selection-policy).
        val enumerator = ResourceCandidates(
            listOf(
                datedResource("OLD-primary.jpg", "OLD", "2026-07-01T00:00:00Z"),
                datedResource("NEW-primary.jpg", "NEW", "2026-07-10T00:00:00Z"),
            ),
        )
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(admitting())

        assertEquals(1, source.size.value, "only the post-cutoff asset (NEW) counts toward the total")
    }

    @Test
    fun `an undated asset is excluded under a cutoff`() = runTest {
        val enumerator = ResourceCandidates(listOf(undatedResource("U-primary.jpg", "U")))
        val source = OwnDeviceGalleryStatusSource(enumerator)

        source.refresh(admitting())

        assertEquals(0, source.size.value, "an asset with no creationDate is out of scope under a cutoff")
    }
}
