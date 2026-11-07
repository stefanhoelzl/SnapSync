package app.snapsync.domain.report

import kotlin.test.Test
import kotlin.test.assertEquals

/** A fact that could not be read is written failed with its reason — cut to the bound. */
class FactTest {
    @Test
    fun `a failed fact is shown failed with its reason, cut to the bound`() {
        assertEquals("failed (no thermal service)", Fact.Failed("no thermal service").shown)
        assertEquals("failed (${"x".repeat(Fact.FAILURE_REASON_CHARS)})", Fact.Failed("x".repeat(500)).shown)
    }
}
