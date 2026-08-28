package app.snapsync.feature.status

import app.snapsync.model.Candidate
import app.snapsync.model.CandidateRead
import app.snapsync.model.CaptureDate
import app.snapsync.model.AssetFacts
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.selectionRulesFor
import app.snapsync.ports.CandidateSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

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
        eventId = "E",
        name = "Event",
        direction = Direction.Both,
        minPhotoDate = captureCutoff("2026-01-01T00:00:00Z"),
        maxPhotoDate = captureCeiling("2026-12-31T00:00:00Z"),
    )

    private suspend fun policy(): SelectionPolicy = SelectionPolicy(
        selectionRulesFor(
            includesUpload = true,
            cutoff = captureCutoff("2026-01-01T00:00:00Z"),
            ceiling = null,
            suppressedAssetIds = { emptySet() },
            albumExcludedAssetIds = { emptySet() },
        ),
    )

    /** One asset, so a completed walk publishes a count of 1 rather than an ambiguous 0. */
    private class OneAsset : CandidateSource {
        override suspend fun candidates(policy: SelectionPolicy): CandidateRead = CandidateRead.Readable(
            listOf(
                object : Candidate {
                    override val facts = AssetFacts("A", CaptureDate("2026-06-01T00:00:00Z"))
                    override suspend fun resources(): List<Resource> = emptyList()
                },
            ),
        )
    }

    /** Records the ORDER of every step, which is the whole subject of this file. */
    private fun harness(
        source: CandidateSource = OneAsset(),
        activeConfig: () -> EventConfig? = { config },
        policyFor: suspend (EventConfig) -> SelectionPolicy = { policy() },
    ): Pair<MutableList<String>, StatusRefresh> {
        val steps = mutableListOf<String>()
        val gallery = OwnDeviceGalleryStatusSource(
            object : CandidateSource {
                override suspend fun candidates(policy: SelectionPolicy): CandidateRead {
                    steps += "walk"
                    return source.candidates(policy)
                }
            },
        )
        val refresh = StatusRefresh(
            ledgerCounts = ReadingLedgerCountsSource {
                steps += "ledger"
                LedgerCounts(completed = 1, pending = 0)
            },
            gallery = gallery,
            refreshDownloadLine = { steps += "downloads" },
            activeConfig = activeConfig,
            policyFor = { cfg -> steps += "policy"; policyFor(cfg) },
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
    fun `a completed refresh publishes the counted total`() = runTest {
        // The ordering assertions above would all pass against a method that walked and threw the
        // answer away, so pin that the sequence actually produces `N`.
        val gallery = OwnDeviceGalleryStatusSource(OneAsset())
        val counts = ReadingLedgerCountsSource { LedgerCounts(completed = 3, pending = 1) }
        StatusRefresh(
            ledgerCounts = counts,
            gallery = gallery,
            refreshDownloadLine = {},
            activeConfig = { config },
            policyFor = { policy() },
        ).run()
        assertEquals(1, gallery.size.value, "N is the admitted own-asset count")
        assertEquals(LedgerCounts(completed = 3, pending = 1), counts.counts.value, "and the counts are read")
    }

    @Test
    fun `no membership counts nothing and never walks`() = runTest {
        // `N` stays null — NOT COUNTED. A zero here would settle the screen at "In sync" on a device
        // that has counted nothing (capability `gallery-status`).
        val (steps, refresh) = harness(activeConfig = { null })
        refresh.run()
        assertEquals(listOf("ledger", "downloads"), steps, "the cheap reads still run; nothing else does")
    }

    @Test
    fun `a failed policy read leaves N unrefreshed without cancelling the cheap reads`() = runTest {
        // The spec's *One failing refresh does not cancel the others*: this runs as one child of the
        // Foreground flow's coroutineScope, so an escaping failure would take the download reconcile,
        // the staged-byte reclaim and the membership refresh with it.
        val (steps, refresh) = harness(policyFor = { error("album lookup blew up") })
        refresh.run() // must NOT throw
        assertEquals(listOf("ledger", "downloads", "policy"), steps, "the walk never started")
        assertTrue("walk" !in steps)
    }

    @Test
    fun `cancellation propagates rather than being logged as a policy failure`() = runTest {
        // The leftover this change closes. `runCatching` catches CancellationException like anything
        // else; swallowing it breaks structured concurrency AND posts an Error line — which reaches the
        // crash reporter on production builds — for an ordinary teardown.
        val (_, refresh) = harness(policyFor = { throw CancellationException("scope torn down") })
        assertFailsWith<CancellationException> { refresh.run() }
    }
}
