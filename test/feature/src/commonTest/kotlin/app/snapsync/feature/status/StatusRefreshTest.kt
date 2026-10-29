package app.snapsync.feature.status

import app.snapsync.data.sharing.Contribution
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.feature.support.CapturingLogWriter
import app.snapsync.feature.support.OwnLibrary
import app.snapsync.feature.support.Recognised
import app.snapsync.feature.support.captureRange
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.photo
import app.snapsync.format.TimestampText
import app.snapsync.model.EventConfig
import app.snapsync.services.membership.MembershipProjection
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testEventId
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The status-refresh **order**, asserted for the first time (capability `sync-status`, "Foreground
 * status refresh is not sequenced behind the upload pump" — scenario *The cheap reads precede the
 * enumeration*).
 *
 * The rule shipped a regression and then lived as a comment inside a `compose/` lambda, where nothing
 * could reach it: the wiring graph is smoke-tested end to end and never unit-tested, so "cheap reads
 * first" was enforced by whoever next edited the method. Seating it in a feature is what makes the
 * SHALL testable rather than merely written down.
 */
class StatusRefreshTest {

    private val config = EventConfig(
        eventId = testEventId("E"),
        name = "Event",
        direction = Direction.Both,
        minPhotoDate = CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-01-31T00:00:00Z")),
        endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
    )

    /** One asset in the membership's range, so a completed walk publishes a count of 1 rather than an ambiguous 0. */
    private fun oneAsset(): OwnLibrary = OwnLibrary().apply { add(photo("A", "2026-01-15T00:00:00Z")) }

    /** The real derivation, over [library]. */
    private fun derivedBy(library: OwnLibrary): suspend (Membership) -> Contribution =
        { library.photos.contribution(it, received = emptySet()) }

    /** Records the ORDER of every step, which is the whole subject of this file. */
    private fun harness(
        library: OwnLibrary = oneAsset(),
        activeConfig: EventConfig? = config,
        contributionFor: suspend (Membership) -> Contribution = derivedBy(library),
        gallery: OwnDeviceGalleryStatusSource? = null,
        log: Logger = Logger.withTag("status"),
    ): Pair<MutableList<String>, StatusRefresh> {
        val steps = mutableListOf<String>()
        library.gallery.onWalk = { steps += "walk" }
        val refresh = StatusRefresh(
            ledgerCounts = ReadingLedgerCountsSource {
                steps += "ledger"
                LedgerCounts(done = setOf(testAssetId("d1")), pending = emptySet())
            },
            gallery = gallery ?: OwnDeviceGalleryStatusSource(library.photos),
            refreshDownloadLine = { steps += "downloads" },
            recognition = Recognised(listOfNotNull(activeConfig?.eventId)),
            configSource = membership(activeConfig),
            contributionFor = { m ->
                steps += "policy"
                contributionFor(m)
            },
            log = log,
        )
        return steps to refresh
    }

    @Test
    fun `the cheap reads precede the enumeration`() = runTest {
        // THE RULE. The ledger `aggregates()` and the download projection are SQLite reads; the walk is
        // ~6 s. Walking first published a counted TOTAL beside counts nobody had read, so a device that
        // had shared everything rendered "Syncing…" with an upload arrow.
        val (steps, refresh) = harness()
        refresh.run()
        assertEquals(listOf("ledger", "downloads", "policy", "walk"), steps)
    }

    @Test
    fun `an unrecognised membership is not counted - N stays not counted`() = runTest {
        // Before the received photos were recognised, any of them would count as the member's own: the upload gate
        // withholds until then, so `N` must not count them either (capability `receiving-photos`).
        val library = oneAsset()
        val gallery = OwnDeviceGalleryStatusSource(library.photos)
        StatusRefresh(
            ledgerCounts = ReadingLedgerCountsSource { LedgerCounts(done = emptySet(), pending = emptySet()) },
            gallery = gallery,
            refreshDownloadLine = {},
            recognition = Recognised(),
            configSource = membership(config),
            contributionFor = derivedBy(library),
        ).run()
        assertNull(gallery.admitted.value, "not counted, so the screen stays on its neutral line")
    }

    @Test
    fun `a completed refresh publishes the counted total`() = runTest {
        // The ordering assertions above would all pass against a method that walked and threw the
        // answer away, so pin that the sequence actually produces `N`.
        val library = oneAsset()
        val gallery = OwnDeviceGalleryStatusSource(library.photos)
        val counts =
            ReadingLedgerCountsSource {
                LedgerCounts(
                    done = setOf(testAssetId("d1"), testAssetId("d2"), testAssetId("d3")),
                    pending = setOf(testAssetId("p1")),
                )
            }
        StatusRefresh(
            ledgerCounts = counts,
            gallery = gallery,
            refreshDownloadLine = {},
            recognition = Recognised(listOf(config.eventId)),
            configSource = membership(config),
            contributionFor = derivedBy(library),
        ).run()
        assertEquals(setOf(testAssetId("A")), gallery.admitted.value, "N is the admitted own-asset count")
        assertEquals(
            LedgerCounts(
                done = setOf(testAssetId("d1"), testAssetId("d2"), testAssetId("d3")),
                pending = setOf(testAssetId("p1")),
            ),
            counts.counts.value,
            "and the counts are read",
        )
    }

    @Test
    fun `no membership counts nothing and never walks`() = runTest {
        // `N` stays null — NOT COUNTED. A zero here would settle the screen at "In sync" on a device
        // that has counted nothing (capability `sync-status`).
        val (steps, refresh) = harness(activeConfig = null)
        refresh.run()
        assertEquals(listOf("ledger", "downloads"), steps, "the cheap reads still run; nothing else does")
    }

    @Test
    fun `a failed policy read leaves N unrefreshed without cancelling the cheap reads`() = runTest {
        // The spec's *One failing refresh does not cancel the others*: this runs as one child of the
        // Foreground flow's coroutineScope, so an escaping failure would take the download reconcile,
        // the staged-byte reclaim and the membership refresh with it.
        val (steps, refresh) = harness(contributionFor = { error("album lookup blew up") })
        refresh.run() // must NOT throw
        assertEquals(listOf("ledger", "downloads", "policy"), steps, "the walk never started")
        assertTrue("walk" !in steps)
    }

    @Test
    fun `the group is exactly the two cheap reads and never the enumeration`() = runTest {
        // The group has TWO callers — this class's `run`, and the foreground-gated poll — and stating its
        // membership in both places is how the poll came to tick only the ledger, leaving the download line
        // unbounded. One definition, asserted here; the poll's own test asserts it reaches both arms.
        val (steps, refresh) = harness()
        refresh.refreshCheapLocalReads()
        assertEquals(listOf("ledger", "downloads"), steps, "both arms, and nothing slower")
        assertTrue("walk" !in steps, "the enumeration is not a member of the group")
        assertTrue("policy" !in steps, "nor is the policy derivation it needs")
    }

    @Test
    fun `a failing member neither cancels its sibling nor the enumeration`() = runTest {
        // Both members keep last-good on a failed read, so neither can take down the Foreground flow's
        // other children. The ledger source already had this posture; the download line gained it with
        // the group, which is what makes the two members uniform rather than one safe by accident.
        val steps = mutableListOf<String>()
        val library = oneAsset()
        val gallery = OwnDeviceGalleryStatusSource(library.photos)
        StatusRefresh(
            ledgerCounts = ReadingLedgerCountsSource {
                steps += "ledger"
                error("the ledger read blew up")
            },
            gallery = gallery,
            refreshDownloadLine = { steps += "downloads" },
            recognition = Recognised(listOf(config.eventId)),
            configSource = membership(config),
            contributionFor = {
                steps += "policy"
                derivedBy(library)(it)
            },
        ).run() // must NOT throw

        assertEquals(listOf("ledger", "downloads", "policy"), steps, "the sibling and the walk still ran")
        assertEquals(1, gallery.admitted.value?.size, "and the enumeration still published N")
    }

    @Test
    fun `cancellation propagates rather than being logged as a policy failure`() = runTest {
        // The leftover this change closes. `runCatching` catches CancellationException like anything
        // else; swallowing it breaks structured concurrency AND posts an Error line — which reaches the
        // crash reporter on production builds — for an ordinary teardown.
        val (_, refresh) = harness(contributionFor = { throw CancellationException("scope torn down") })
        assertFailsWith<CancellationException> { refresh.run() }
    }

    // ---- the library half alone: what a selection change runs ----

    @Test
    fun `the total alone recounts N without the cheap reads`() = runTest {
        val library = oneAsset()
        val gallery = OwnDeviceGalleryStatusSource(library.photos)
        val (steps, refresh) = harness(library = library, gallery = gallery)
        refresh.refreshTotal()
        assertEquals(listOf("policy", "walk"), steps, "neither cheap read runs")
        assertEquals(setOf(testAssetId("A")), gallery.admitted.value)
    }

    @Test
    fun `the total alone while unjoined counts nothing`() = runTest {
        val library = oneAsset()
        val gallery = OwnDeviceGalleryStatusSource(library.photos)
        val (steps, refresh) = harness(library = library, activeConfig = null, gallery = gallery)
        refresh.refreshTotal()
        assertEquals(emptyList(), steps)
        assertNull(gallery.admitted.value, "N stays not counted")
    }

    @Test
    fun `the total alone under a failed policy read is logged and keeps N`() = runTest {
        val library = oneAsset()
        val gallery = OwnDeviceGalleryStatusSource(library.photos).apply {
            refresh(library.contribution(captureRange("2026-01-01T00:00:00Z")))
        }
        val recorder = CapturingLogWriter()
        val (_, refresh) = harness(
            library = library,
            contributionFor = { error("album lookup blew up") },
            gallery = gallery,
            log = recorder.logger(),
        )
        refresh.refreshTotal() // must NOT throw
        assertEquals(setOf(testAssetId("A")), gallery.admitted.value, "N keeps what it last honestly held")
        assertTrue(recorder.lines.any { (severity, line) -> severity == Severity.Error && "N not refreshed" in line })
    }
}

/** The real membership service, holding a fixed answer. */
private fun membership(config: EventConfig?): MembershipProjection = configService(config)
