package app.snapsync.gallery

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionRulesFor
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.MIN_IMAGE_PIXEL_AREA
import app.snapsync.model.MIN_VIDEO_PIXEL_AREA
import app.snapsync.model.SelectionRule
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **rule → `PHFetchOptions` translation** (capability `photo-selection-policy`).
 *
 * Runs on the simulator rather than the JVM because the predicate is built with real `NSPredicate`; the
 * SDK-pinned subtype constants it inlines are asserted against PhotoKit in `PhotoKitAssetFactsTest`.
 *
 * What matters here is not that the predicate is *clever* — narrowing is an optimization the authoritative
 * in-memory admission is proven to work without — but that it is **honest about what it declined**. A rule
 * PhotoKit cannot express must fall through to the admission, and the `when` must force that choice to be
 * made explicitly rather than by omission.
 */
class PhotoKitCandidateSourceTest {

    private val cutoff = captureCutoff("2026-06-01T00:00:00Z")
    private val ceiling = captureCeiling("2026-06-30T00:00:00Z")

    // Rule lists are built directly here rather than through the one derivation: this file is about the
    // TRANSLATOR, so it must be able to present arbitrary rule sets — including ones the derivation would
    // never emit. The floor is prepended because every contributing membership carries one; pass
    // `floor =` (named — it follows a vararg) only when the bound itself is what the test is about.
    private fun admitting(vararg rest: SelectionRule, floor: CaptureCutoff = cutoff) =
        SelectionPolicy(listOf(SelectionRule.CaptureAfter(floor)) + rest)

    @Test
    fun `a non-contributing policy narrows to nothing`() {
        // The deny-everything rule is translated into a query matching NO asset — not into "no predicate",
        // which PhotoKit reads as "everything". Correctness never depended on this (the caller's admission
        // refuses every asset regardless), but without it a non-contributing membership pays a
        // whole-library walk on every cold start to reach the empty set its configuration already stated.
        //
        // Deliberately built from an unsatisfiable `creationDate` comparison — the same key the bounds
        // use — and NOT from the `(mediaSubtypes & N) == 0` form that also returns zero rows. That form is
        // an artefact of the predicate parser: were Apple to evaluate it correctly, this would begin
        // admitting the whole library.
        val predicate = predicateFor(SelectionPolicy(listOf(SelectionRule.DenyAll)))
        assertNotNull(predicate)
        assertTrue(
            predicate.predicateFormat.contains("creationDate <"),
            "expected an unsatisfiable creationDate comparison, got: ${predicate.predicateFormat}",
        )
    }

    @Test
    fun `the capture floor is always pushed`() {
        // The ONE required narrowing: an unbounded walk is watchdog-killed before the authoritative
        // admission ever runs, so this is liveness rather than correctness.
        // No rule is supplied at all: the floor rides on the variant, so it cannot be forgotten.
        val predicate = predicateFor(admitting())
        assertTrue(predicate!!.predicateFormat.contains("creationDate >="))
    }

    @Test
    fun `the ceiling is pushed too`() {
        val predicate = predicateFor(admitting(SelectionRule.CaptureBefore(ceiling)))
        assertTrue(predicate!!.predicateFormat.contains("creationDate <="))
    }

    @Test
    fun `subtype exclusions use the NOT form`() {
        // Device-verified: `(mediaSubtypes & N) == 0` returns ZERO ROWS, silently, without raising —
        // shipping it would starve the walk of every asset. The NOT-form is the only one that works.
        val predicate = predicateFor(
            admitting(SelectionRule.ExcludeScreenshots, SelectionRule.ExcludeScreenRecordings),
        )!!
        assertTrue(predicate.predicateFormat.contains("NOT"), "the == 0 form returns zero rows on device")
        assertTrue(predicate.predicateFormat.contains("mediaSubtypes"), "plural key — the singular one empties the library")
    }

    @Test
    fun `the rules PhotoKit cannot express contribute nothing`() {
        // Predicate arithmetic aborts the process, `hasAdjustments` is not a supported key, and the echo
        // and album sets are ids rather than asset properties. All four must fall through in silence —
        // and, crucially, WITHOUT producing a predicate that looks like it narrowed.
        assertNull(
            predicateFor(
                admitting(
                    SelectionRule.MinImageArea(3_000_000),
                    SelectionRule.MinVideoArea(1280L * 720L),
                    SelectionRule.NotEcho(setOf("A")),
                    SelectionRule.NotInDenylistedAlbum(setOf("B")),
                    // The floor is mandatory on the variant, so "only unexpressible rules" is reachable
                    // only when the bound itself fails to parse and its clause drops. Same assertion,
                    // same reason — the state is now expressed through the one door that still opens it.
                    floor = captureCutoff("not-a-date"),
                ),
            ),
            "an unexpressible rule must not yield a predicate — a partial one would look like a narrowing",
        )
    }

    @Test
    fun `a full policy narrows by what it can and drops the rest`() {
        val predicate = predicateFor(
            admitting(
                SelectionRule.CaptureBefore(ceiling),
                SelectionRule.ExcludeScreenshots,
                SelectionRule.ExcludeScreenRecordings,
                SelectionRule.MinImageArea(MIN_IMAGE_PIXEL_AREA),
                SelectionRule.MinVideoArea(MIN_VIDEO_PIXEL_AREA),
            ),
        )!!
        val format = predicate.predicateFormat
        assertTrue(format.contains("creationDate >="), "floor")
        assertTrue(format.contains("creationDate <="), "ceiling")
        assertTrue(format.contains("mediaSubtypes"), "subtypes")
        // The area floors are absent by necessity, not oversight — arithmetic aborts the process.
        assertTrue(!format.contains("pixelWidth"), "an area comparison would abort the process")
        assertTrue(!format.contains("hasAdjustments"), "not a supported key — likewise aborts")
    }

    @Test
    fun `the date bounds are widened rather than narrowed`() {
        // The authoritative compare is lexicographic on strings; this is an NSDate comparison. Where they
        // could disagree at a boundary, over-returning costs a few round-trips the admission drops, while
        // under-returning silently loses a photo nothing can add back.
        val predicate = predicateFor(
            admitting(
                SelectionRule.CaptureBefore(ceiling),
                SelectionRule.ExcludeScreenshots,
                SelectionRule.ExcludeScreenRecordings,
                SelectionRule.MinImageArea(MIN_IMAGE_PIXEL_AREA),
                SelectionRule.MinVideoArea(MIN_VIDEO_PIXEL_AREA),
            ),
        )!!
        // A day of slack on each side: the format carries the widened instants, not the exact bounds.
        assertTrue(!predicate.predicateFormat.contains("2026-06-01 00:00:00"), "the floor is widened earlier")
    }

    @Test
    fun `an unparseable bound drops that clause rather than the whole fetch`() {
        // A bound that cannot be parsed must not take the rest of the predicate with it — and must not
        // silently become "fetch nothing", which is the failure mode that hides as "sync is just slow".
        val predicate = predicateFor(
            admitting(SelectionRule.ExcludeScreenshots, floor = captureCutoff("not-a-date")),
        )!!
        assertTrue(predicate.predicateFormat.contains("mediaSubtypes"), "the other clauses survive")
        assertEquals(false, predicate.predicateFormat.contains("creationDate"))
    }
}
