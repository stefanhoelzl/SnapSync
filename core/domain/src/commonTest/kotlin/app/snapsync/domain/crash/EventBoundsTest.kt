package app.snapsync.domain.crash

import app.snapsync.domain.report.DiagnosticDump
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventBoundsTest {

    private val String.bytes get() = encodeToByteArray().size

    @Test
    fun aTextWithinTheCapIsUntouched() {
        assertEquals("short line", EventBounds.cap("short line", 64))
    }

    @Test
    fun aTextExactlyAtTheCapIsUntouched() {
        val text = "x".repeat(64)
        assertEquals(text, EventBounds.cap(text, 64))
    }

    @Test
    fun oneByteOverIsCutAndSaysHowMuchWasDropped() {
        val capped = EventBounds.cap("x".repeat(65), 64)
        assertTrue(capped.bytes <= 64, "got ${capped.bytes} bytes")
        val kept = capped.substringBefore("…")
        assertEquals("…[+${65 - kept.length} B]", capped.removePrefix(kept))
    }

    @Test
    fun aLongTextIsCutToTheCapWithTheMarkerInside() {
        val capped = EventBounds.cap("y".repeat(10_000), EventBounds.BREADCRUMB_TEXT_BYTES)
        assertTrue(capped.bytes <= EventBounds.BREADCRUMB_TEXT_BYTES)
        assertTrue(capped.bytes >= EventBounds.BREADCRUMB_TEXT_BYTES - 3, "the cut wastes no room: ${capped.bytes}")
        assertTrue(capped.endsWith("…[+${10_000 - capped.substringBefore("…").length} B]"))
    }

    @Test
    fun aCutNeverSplitsAMultiByteSequence() {
        // 4-byte code points: every cut position but one in four would land inside a sequence.
        val emoji = "😀".repeat(100)
        for (cap in 30..60) {
            val capped = EventBounds.cap(emoji, cap)
            assertTrue(capped.bytes <= cap, "cap $cap got ${capped.bytes}")
            val kept = capped.substringBefore("…")
            assertEquals("😀".repeat(kept.encodeToByteArray().size / 4), kept, "cap $cap split a code point")
            assertEquals("…[+${emoji.bytes - kept.bytes} B]", capped.removePrefix(kept))
        }
    }

    @Test
    fun theMarkerCountsBytesNotChars() {
        val capped = EventBounds.cap("ü".repeat(100), 40) // 200 bytes, 100 chars
        val kept = capped.substringBefore("…")
        assertEquals("…[+${200 - kept.bytes} B]", capped.removePrefix(kept))
    }

    @Test
    fun sharedCapGivesTheFirstTextPriority() {
        val (message, data) = EventBounds.capAll(
            listOf("m".repeat(2_000), "d".repeat(100)),
            EventBounds.BREADCRUMB_TEXT_BYTES,
        )
        assertTrue(message.bytes + data.bytes <= EventBounds.BREADCRUMB_TEXT_BYTES)
        assertTrue(message.startsWith("mmm") && message.contains("…[+"))
        assertEquals("", data)
    }

    @Test
    fun sharedCapLeavesTextsThatFitWhole() {
        val texts = listOf("message", "a=1", "b=2")
        assertEquals(texts, EventBounds.capAll(texts, EventBounds.BREADCRUMB_TEXT_BYTES))
    }

    @Test
    fun sharedCapCutsTheFirstTextThatOverflowsAndStaysWithinTheTotal() {
        val texts = listOf("m".repeat(200), "d".repeat(2_000), "e".repeat(10))
        val capped = EventBounds.capAll(texts, EventBounds.BREADCRUMB_TEXT_BYTES)
        assertEquals(texts[0], capped[0])
        assertTrue(capped[1].contains("…[+"))
        assertTrue(capped.sumOf { it.bytes } <= EventBounds.BREADCRUMB_TEXT_BYTES, "total ${capped.sumOf { it.bytes }}")
    }

    @Test
    fun theCapsSumBelowTheCeiling() {
        // The whole-event sum from DiagnosticDump.LOG_BUDGET_BYTES' KDoc; the contract clause measures the real total.
        val logs = DiagnosticDump.LOG_BUDGET_BYTES * 101 / 100
        // A crumb of quotes doubles when escaped.
        val crumbs = EventBounds.MAX_BREADCRUMBS * (2 * EventBounds.BREADCRUMB_TEXT_BYTES + 300)
        assertTrue(logs + 4_000 + crumbs + 8_000 + 100_000 < EventBounds.MAX_EVENT_BYTES)
    }
}
