package app.snapsync.domain.asset

import app.snapsync.domain.time.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class CaptureDateTest {

    @Test
    fun `a dated capture holds its timestamp — and no date is a case of its own`() {
        val at = Timestamp.of(Instant.parse("2026-07-06T14:32:11Z"))
        val dated: CaptureDate = CaptureDate.Dated(at)
        assertEquals(at, (dated as CaptureDate.Dated).at)
        assertNotEquals<CaptureDate>(dated, CaptureDate.Undated)
        assertEquals("Undated", CaptureDate.Undated.toString())
    }
}
