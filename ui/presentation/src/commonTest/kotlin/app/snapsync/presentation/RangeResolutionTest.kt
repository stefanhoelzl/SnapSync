package app.snapsync.presentation

import app.snapsync.model.CaptureDate
import app.snapsync.model.Direction
import app.snapsync.model.FromChoice
import app.snapsync.model.UntilChoice
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capture-range resolution rules, tested **directly** (capability `photo-selection-policy`).
 *
 * They live beside the reduction that calls them: the resolution decides what a join or a reconfigure
 * would COMMIT, which is a presentation concern, not a rendering one.
 *
 * These four functions decide what a join or a reconfigure would COMMIT — the bounds, the direction, and
 * whether "Now" is even offered. Until this file existed they were reachable only through a Compose UI
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
    fun `the event-end preset resolves to the window end`() {
        assertEquals(windowEnd, resolveUntil(UntilChoice.EVENT_END, null, windowStart, windowEnd))
        // A custom value is IGNORED while the preset is Event end — the preset is what the member chose.
        assertEquals(windowEnd, resolveUntil(UntilChoice.EVENT_END, insideWindow, windowStart, windowEnd))
    }

    @Test
    fun `a custom until with no picked value falls back to the window end`() {
        assertEquals(windowEnd, resolveUntil(UntilChoice.CUSTOM, null, windowStart, windowEnd))
    }

    @Test
    fun `a custom until inside the window is taken as picked`() {
        assertEquals(insideWindow, resolveUntil(UntilChoice.CUSTOM, insideWindow, windowStart, windowEnd))
    }

    @Test
    fun `a custom until outside the window is coerced back into it`() {
        assertEquals(windowStart, resolveUntil(UntilChoice.CUSTOM, beforeWindow, windowStart, windowEnd))
        assertEquals(windowEnd, resolveUntil(UntilChoice.CUSTOM, afterWindow, windowStart, windowEnd))
    }

    // ── resolveFrom ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the event-start preset resolves to the window start`() {
        assertEquals(windowStart, resolveFrom(FromChoice.EVENT_START, null, windowStart, nowInside, windowEnd))
        // As with Until, a stale custom value does not leak through a non-custom preset.
        assertEquals(windowStart, resolveFrom(FromChoice.EVENT_START, insideWindow, windowStart, nowInside, windowEnd))
    }

    @Test
    fun `the now preset resolves to now while now is inside the window`() {
        assertEquals(nowInside, resolveFrom(FromChoice.NOW, null, windowStart, nowInside, windowEnd))
    }

    @Test
    fun `a custom from with no picked value falls back to the window start`() {
        assertEquals(windowStart, resolveFrom(FromChoice.CUSTOM, null, windowStart, nowInside, windowEnd))
    }

    @Test
    fun `a custom from inside the window is taken as picked`() {
        assertEquals(insideWindow, resolveFrom(FromChoice.CUSTOM, insideWindow, windowStart, nowInside, windowEnd))
    }

    @Test
    fun `a from below the window start is floored to it`() {
        assertEquals(windowStart, resolveFrom(FromChoice.CUSTOM, beforeWindow, windowStart, nowInside, windowEnd))
    }

    @Test
    fun `a from above the resolved until is capped to that until rather than to the window end`() {
        // The ceiling is the RESOLVED until, not the window's end — a member who narrowed the upper bound
        // must not be able to push the lower bound past it.
        val until = insideWindow
        assertEquals(until, resolveFrom(FromChoice.CUSTOM, afterWindow, windowStart, nowInside, until))
        // "Now" is subject to the same cap: a member who picks Now after their own until gets the until.
        assertEquals(until, resolveFrom(FromChoice.NOW, null, windowStart, afterWindow, until))
    }

    // ── the invariant the resolution ORDER exists for ────────────────────────────────────────────

    @Test
    fun `every preset pair resolves to a non-inverted range inside the window`() {
        // Every custom value a picker could hold (including outside the window on both sides), every
        // preset pair, and every "now" the clock could report relative to the window.
        val customs = listOf(null, beforeWindow, insideWindow, afterWindow, windowStart, windowEnd)
        val nows = listOf(beforeWindow, nowInside, afterWindow)
        val cases = FromChoice.entries.flatMap { fromPreset ->
            UntilChoice.entries.flatMap { untilPreset ->
                customs.flatMap { fromCustom ->
                    customs.flatMap { untilCustom -> nows.map { Case(fromPreset, fromCustom, untilPreset, untilCustom, it) } }
                }
            }
        }
        for (c in cases) assertResolvesInsideWindow(c)
    }

    private class Case(
        val fromPreset: FromChoice,
        val fromCustom: LocalDateTime?,
        val untilPreset: UntilChoice,
        val untilCustom: LocalDateTime?,
        val now: LocalDateTime,
    )

    private fun assertResolvesInsideWindow(c: Case) {
        val until = resolveUntil(c.untilPreset, c.untilCustom, windowStart, windowEnd)
        val from = resolveFrom(c.fromPreset, c.fromCustom, windowStart, c.now, until)
        val case = "${c.fromPreset}/${c.fromCustom} .. ${c.untilPreset}/${c.untilCustom} @ ${c.now}"
        assertTrue(from <= until, "range inverted for $case: $from > $until")
        assertTrue(from >= windowStart, "from below the window for $case: $from")
        assertTrue(until <= windowEnd, "until above the window for $case: $until")
        assertTrue(until >= windowStart, "until below the window for $case: $until")
    }

    // ── the COMPOSITION of the two, which was untestable while it lived in a Composable ──────────

    @Test
    fun `resolve floors the lower bound to the resolved upper bound rather than the window end`() {
        // The regression this catches: resolving `from` against the WINDOW END instead of the resolved
        // `until`. Every rendered label still looks plausible, so no UI test distinguishes them — which is
        // exactly what made this case unreachable while the composition lived in a private @Composable.
        val form = RangeForm(
            fromPreset = FromChoice.CUSTOM,
            fromCustom = afterWindow,
            untilPreset = UntilChoice.CUSTOM,
            untilCustom = insideWindow,
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
        val counted = form.resolve(windowStart, windowEnd, nowInside, true, ::stubCutoff, shareableCount = 0)
        val absent = form.resolve(windowStart, windowEnd, nowInside, true, ::stubCutoff, shareableCount = null)
        // Absent and zero are different answers and stay distinguishable (capability `join-share-count`).
        assertEquals(0, counted.shareableCount)
        assertEquals(null, absent.shareableCount)
    }

    /** A fixed-shape cutoff conversion — the resolution is under test, not the formatter. */
    private fun stubCutoff(local: LocalDateTime): CaptureDate =
        CaptureDate("${local.year}-${local.month.ordinal + 1}-${local.day}T${local.hour}:${local.minute}:00Z")

    // ── directionOf ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `both switches on is Both and each alone is its own direction`() {
        assertEquals(Direction.Both, directionOf(shareOn = true, receiveOn = true))
        assertEquals(Direction.UploadOnly, directionOf(shareOn = true, receiveOn = false))
        assertEquals(Direction.DownloadOnly, directionOf(shareOn = false, receiveOn = true))
    }

    @Test
    fun `both switches off yields the inert placeholder rather than throwing`() {
        // The dead case never reaches a commit — the commit button is disabled there — so the value is
        // arbitrary but must be TOTAL: a resolver that threw here would crash a screen the member can
        // legitimately put into this state.
        assertEquals(Direction.DownloadOnly, directionOf(shareOn = false, receiveOn = false))
    }

    // ── nowWithinWindow ─────────────────────────────────────────────────────────────────────────

    private fun d(iso: String) = CaptureDate(iso)

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
}
