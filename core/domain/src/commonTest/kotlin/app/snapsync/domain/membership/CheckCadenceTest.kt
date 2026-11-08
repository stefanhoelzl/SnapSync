package app.snapsync.domain.membership

import app.snapsync.domain.event.ts
import kotlin.test.Test
import kotlin.test.assertEquals

/** A bounded wake reads the server about an event at most once an hour (`changes/timely-background-receiving`, D5). */
class CheckCadenceTest {

    private val now = ts("2026-07-22T12:00:00Z")

    @Test
    fun `no record, or none readable, is due`() {
        assertEquals(CheckCadence.Due, CheckCadence.of(CheckedAt.Never, now))
        assertEquals(CheckCadence.Due, CheckCadence.of(CheckedAt.Unreadable, now))
    }

    @Test
    fun `a read within the hour answers for it`() {
        assertEquals(CheckCadence.NotYet, CheckCadence.of(CheckedAt.At(ts("2026-07-22T11:00:01Z")), now))
        assertEquals(CheckCadence.Due, CheckCadence.of(CheckedAt.At(ts("2026-07-22T11:00:00Z")), now))
    }

    @Test
    fun `a read the clock puts in the future says nothing`() {
        assertEquals(CheckCadence.Due, CheckCadence.of(CheckedAt.At(ts("2026-07-22T12:00:01Z")), now))
    }
}
