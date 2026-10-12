package app.snapsync.presentation

import app.snapsync.model.CaptureDate
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.localToCutoff
import app.snapsync.ui.state.OutsideReach
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.ShareCount
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The capture-range resolution rules, tested **directly** (capability `photo-sharing`).
 *
 * They live beside the reduction that calls them: the resolution decides what a join or a reconfigure
 * would COMMIT, which is a presentation concern, not a rendering one.
 *
 * These four functions decide what a join or a reconfigure would COMMIT — the bounds, the direction, and
 * whether "From now" is even offered. Until this file existed they were reachable only through a Compose UI
 * test: a range that inverted, or a clamp that stopped clamping, would surface as a wrong string in a
 * rendered row rather than as a failing rule. The rules are pure, so they are tested as rules.
 *
 * The inversion cases are the point. `until` is resolved FIRST precisely so `from`'s ceiling can be
 * floored to it; swap that order and `resolveFrom` clamps against a bound that has not been computed yet.
 * [every preset pair resolves to a non-inverted range] is what fails when someone does.
 */
class RangeResolutionTest {

    // A three-day event window, and a "now" that sits inside it.
    private val windowStart = LocalDateTime(2026, 7, 10, 9, 0)
    private val windowEnd = LocalDateTime(2026, 7, 13, 18, 0)
    private val nowInside = LocalDateTime(2026, 7, 11, 12, 0)

    private val beforeWindow = LocalDateTime(2026, 7, 1, 0, 0)
    private val afterWindow = LocalDateTime(2026, 7, 20, 0, 0)
    private val insideWindow = LocalDateTime(2026, 7, 12, 8, 30)

    // ── resolveUntil ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the event-start and from-now presets end at the window end`() {
        assertEquals(windowEnd, resolveUntil(RangeChoice.EVENT_START, null, windowStart, windowEnd))
        assertEquals(windowEnd, resolveUntil(RangeChoice.FROM_NOW, null, windowStart, windowEnd))
        // A custom value is IGNORED while a non-custom preset is chosen — the preset is what the member chose.
        assertEquals(windowEnd, resolveUntil(RangeChoice.EVENT_START, insideWindow, windowStart, windowEnd))
        assertEquals(windowEnd, resolveUntil(RangeChoice.FROM_NOW, insideWindow, windowStart, windowEnd))
    }

    @Test
    fun `a custom until with no picked value falls back to the window end`() {
        assertEquals(windowEnd, resolveUntil(RangeChoice.CUSTOM, null, windowStart, windowEnd))
    }

    @Test
    fun `a custom until inside the window is taken as picked`() {
        assertEquals(insideWindow, resolveUntil(RangeChoice.CUSTOM, insideWindow, windowStart, windowEnd))
    }

    @Test
    fun `a custom until after the window is taken as picked — one before it is held to the window start`() {
        // Capability `join-event`: a member's range may end after the event, never before it starts.
        assertEquals(afterWindow, resolveUntil(RangeChoice.CUSTOM, afterWindow, windowStart, windowEnd))
        assertEquals(windowStart, resolveUntil(RangeChoice.CUSTOM, beforeWindow, windowStart, windowEnd))
    }

    // ── resolveFrom ─────────────────────────────────────────────────────────────────────────────

    /** [resolveFrom] over this test's window. */
    private fun from(preset: RangeChoice, custom: LocalDateTime?, now: LocalDateTime, until: LocalDateTime) =
        resolveFrom(preset, custom, windowStart, windowEnd, now, until)

    @Test
    fun `the event-start preset starts at the window start`() {
        assertEquals(windowStart, from(RangeChoice.EVENT_START, null, now = nowInside, until = windowEnd))
        // A stale custom value does not leak through a non-custom preset.
        assertEquals(windowStart, from(RangeChoice.EVENT_START, insideWindow, now = nowInside, until = windowEnd))
    }

    @Test
    fun `the from-now preset starts at now while now is inside the window`() {
        assertEquals(nowInside, from(RangeChoice.FROM_NOW, null, now = nowInside, until = windowEnd))
        assertEquals(nowInside, from(RangeChoice.FROM_NOW, insideWindow, now = nowInside, until = windowEnd))
    }

    @Test
    fun `a custom from with no picked value falls back to the window start`() {
        assertEquals(windowStart, from(RangeChoice.CUSTOM, null, now = nowInside, until = windowEnd))
    }

    @Test
    fun `a custom from inside the window is taken as picked`() {
        assertEquals(insideWindow, from(RangeChoice.CUSTOM, insideWindow, now = nowInside, until = windowEnd))
    }

    @Test
    fun `a custom from before the window start is taken as picked`() {
        assertEquals(beforeWindow, from(RangeChoice.CUSTOM, beforeWindow, now = nowInside, until = windowEnd))
    }

    @Test
    fun `a from never lies after the window end — whatever until`() {
        assertEquals(windowEnd, from(RangeChoice.CUSTOM, afterWindow, now = nowInside, until = afterWindow))
    }

    @Test
    fun `a from above the resolved until is capped to that until rather than to the window end`() {
        // The ceiling is the RESOLVED until, not the window's end — a member who narrowed the upper bound
        // must not be able to push the lower bound past it.
        val until = insideWindow
        assertEquals(until, from(RangeChoice.CUSTOM, afterWindow, now = nowInside, until = until))
        // "From now" is subject to the same cap.
        assertEquals(until, from(RangeChoice.FROM_NOW, null, now = afterWindow, until = until))
    }

    // ── the invariant the resolution ORDER exists for ────────────────────────────────────────────

    @Test
    fun `every preset resolves to a non-inverted range overlapping the window`() {
        // Every custom value a picker could hold (including outside the window on both sides), every
        // preset, and every "now" the clock could report relative to the window.
        val customs = listOf(null, beforeWindow, insideWindow, afterWindow, windowStart, windowEnd)
        val nows = listOf(beforeWindow, nowInside, afterWindow)
        val cases = RangeChoice.entries.flatMap { preset ->
            customs.flatMap { fromCustom ->
                customs.flatMap { untilCustom -> nows.map { Case(preset, fromCustom, untilCustom, it) } }
            }
        }
        for (c in cases) assertResolvesAroundWindow(c)
    }

    private class Case(
        val preset: RangeChoice,
        val fromCustom: LocalDateTime?,
        val untilCustom: LocalDateTime?,
        val now: LocalDateTime,
    )

    private fun assertResolvesAroundWindow(c: Case) {
        val until = resolveUntil(c.preset, c.untilCustom, windowStart, windowEnd)
        val from = resolveFrom(c.preset, c.fromCustom, windowStart, windowEnd, c.now, until)
        val case = "${c.preset} ${c.fromCustom} .. ${c.untilCustom} @ ${c.now}"
        assertTrue(from <= until, "range inverted for $case: $from > $until")
        assertTrue(from <= windowEnd, "from after the window for $case: $from")
        assertTrue(until >= windowStart, "until before the window for $case: $until")
        if (c.preset != RangeChoice.CUSTOM) {
            assertTrue(from >= windowStart && until <= windowEnd, "a preset stays inside the window for $case")
        }
    }

    // ── the COMPOSITION of the two, which was untestable while it lived in a Composable ──────────

    @Test
    fun `resolve floors the lower bound to the resolved upper bound rather than the window end`() {
        // The regression this catches: resolving `from` against the WINDOW END instead of the resolved
        // `until`. Every rendered label still looks plausible, so no UI test distinguishes them — which is
        // exactly what made this case unreachable while the composition lived in a private @Composable.
        val form = RangeForm(
            preset = RangeChoice.CUSTOM,
            customFrom = afterWindow,
            customUntil = insideWindow,
        )
        val r = form.resolve(windowStart, windowEnd, nowInside, nowAvailable = true, toCutoff = ::stubCutoff)
        assertEquals(insideWindow, r.until)
        assertEquals(insideWindow, r.from, "from must clamp to the resolved until, never to the window end")
    }

    @Test
    fun `resolve derives the direction and the commit gate from the switches`() {
        fun gate(share: Boolean, receive: Boolean) =
            RangeForm(shareOn = share, receiveOn = receive)
                .resolve(windowStart, windowEnd, nowInside, nowAvailable = true, toCutoff = ::stubCutoff)
        assertEquals(Direction.Both, gate(share = true, receive = true).direction)
        assertTrue(gate(share = true, receive = false).commitEnabled)
        assertTrue(gate(share = false, receive = true).commitEnabled)
        // Both off is representable and does nothing: the commit is disabled rather than one switch
        // silently flipping the other.
        assertFalse(gate(share = false, receive = false).commitEnabled)
    }

    @Test
    fun `resolve carries the count through untouched including absent`() {
        val form = RangeForm()
        val counted = form.resolve(
            windowStart,
            windowEnd,
            nowInside,
            true,
            ::stubCutoff,
            shareCount = ShareCount.Ready(0),
        )
        val absent = form.resolve(
            windowStart,
            windowEnd,
            nowInside,
            true,
            ::stubCutoff,
            shareCount = ShareCount.Unavailable,
        )
        // Absent and zero are different answers and stay distinguishable (capability `join-event`).
        assertEquals(ShareCount.Ready(0), counted.shareCount)
        assertEquals(ShareCount.Unavailable, absent.shareCount)
    }

    @Test
    fun `the outside reach names the side a range passes the event on`() {
        // Capability `join-event`: the screen says when the chosen range reaches outside the event's dates.
        fun reach(from: LocalDateTime?, until: LocalDateTime?) = outsideReach(
            RangeForm(preset = RangeChoice.CUSTOM, customFrom = from, customUntil = until)
                .resolve(windowStart, windowEnd, nowInside, nowAvailable = true, toCutoff = ::stubCutoff),
        )
        assertNull(reach(null, null), "the event's own dates")
        assertNull(reach(insideWindow, null), "narrower than the event")
        assertEquals(OutsideReach.BEFORE, reach(beforeWindow, null))
        assertEquals(OutsideReach.AFTER, reach(null, afterWindow))
        assertEquals(OutsideReach.BOTH, reach(beforeWindow, afterWindow))
        assertNull(reach(windowStart, windowEnd), "touching both ends is still the event's dates")
    }

    /** A fixed-shape cutoff conversion — the resolution is under test, not the formatter. */
    private fun stubCutoff(local: LocalDateTime): CaptureDate = localToCutoff(local, TimeZone.UTC)

    // ── directionOf ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `both switches on is Both and each alone is its own direction`() {
        assertEquals(Direction.Both, directionOf(shareOn = true, receiveOn = true))
        assertEquals(Direction.UploadOnly, directionOf(shareOn = true, receiveOn = false))
        assertEquals(Direction.DownloadOnly, directionOf(shareOn = false, receiveOn = true))
    }

    @Test
    fun `both switches off is Neither`() {
        // The event's settings apply it (capability `manage-membership`); the join gate never commits it.
        assertEquals(Direction.Neither, directionOf(shareOn = false, receiveOn = false))
    }

    // ── nowWithinWindow ─────────────────────────────────────────────────────────────────────────

    private fun d(iso: String) = CaptureDate.parse(iso)

    @Test
    fun `now inside the window offers the now preset`() {
        assertTrue(nowWithinWindow(d("2026-07-11T12:00:00Z"), d("2026-07-10T09:00:00Z"), d("2026-07-13T18:00:00Z")))
    }

    @Test
    fun `now outside the window on either side does not`() {
        assertFalse(nowWithinWindow(d("2026-07-01T00:00:00Z"), d("2026-07-10T09:00:00Z"), d("2026-07-13T18:00:00Z")))
        assertFalse(nowWithinWindow(d("2026-07-20T00:00:00Z"), d("2026-07-10T09:00:00Z"), d("2026-07-13T18:00:00Z")))
    }

    @Test
    fun `both bounds are inclusive`() {
        val start = d("2026-07-10T09:00:00Z")
        val end = d("2026-07-13T18:00:00Z")
        assertTrue(nowWithinWindow(start, start, end))
        assertTrue(nowWithinWindow(end, start, end))
    }

    @Test
    fun `an unknown start is not the same answer as an absent end`() {
        // Absent START means the window is not known yet, which is NOT "now qualifies" — the details
        // fetch has not resolved. Absent END means no upper bound, which is.
        assertFalse(nowWithinWindow(d("2026-07-11T12:00:00Z"), null, d("2026-07-13T18:00:00Z")))
        assertFalse(nowWithinWindow(d("2026-07-11T12:00:00Z"), null, null))
        assertTrue(nowWithinWindow(d("2026-07-11T12:00:00Z"), d("2026-07-10T09:00:00Z"), null))
    }

    // ── reconfigureForm ─────────────────────────────────────────────────────────────────────────

    private fun membership(from: String, until: String) = EventConfig(
        eventId = "11111111-1111-4111-8111-111111111111",
        name = "Anna's Birthday",
        minPhotoDate = captureCutoff(from),
        startsAt = eventStart("2026-07-06T14:00:00Z"),
        endsAt = eventEnd("2026-07-13T14:00:00Z"),
        maxPhotoDate = captureCeiling(until),
        deletesAt = deletesAt("2099-12-31T00:00:00Z"),
    )

    /** The wall clock is the instant's own digits — the zone is not what is under test. */
    private fun local(at: CaptureDate): LocalDateTime = LocalDateTime.parse(at.iso.removeSuffix("Z"))

    @Test
    fun `a membership spanning the whole window pre-fills the whole event`() {
        val form = reconfigureForm(membership("2026-07-06T14:00:00Z", "2026-07-13T14:00:00Z"), ::local)
        assertEquals(RangeChoice.EVENT_START, form.preset)
        assertNull(form.customFrom)
        assertNull(form.customUntil)
    }

    @Test
    fun `a narrower start pre-fills a custom range with that start and the window end`() {
        // A past "From now" is exactly this: a start after the event's, which is just a custom start now.
        val form = reconfigureForm(membership("2026-07-08T09:30:00Z", "2026-07-13T14:00:00Z"), ::local)
        assertEquals(RangeChoice.CUSTOM, form.preset)
        assertEquals(LocalDateTime(2026, 7, 8, 9, 30), form.customFrom)
        assertNull(form.customUntil, "the end sits on the ceiling, so it resolves to the window end")
    }

    @Test
    fun `a narrower end pre-fills a custom range with that end`() {
        val form = reconfigureForm(membership("2026-07-06T14:00:00Z", "2026-07-10T20:00:00Z"), ::local)
        assertEquals(RangeChoice.CUSTOM, form.preset)
        assertNull(form.customFrom)
        assertEquals(LocalDateTime(2026, 7, 10, 20, 0), form.customUntil)
    }
}
