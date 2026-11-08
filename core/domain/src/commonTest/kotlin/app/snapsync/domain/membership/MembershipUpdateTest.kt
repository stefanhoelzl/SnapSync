package app.snapsync.domain.membership

import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.event.anEvent
import app.snapsync.domain.event.name
import app.snapsync.domain.event.ts
import kotlin.test.Test
import kotlin.test.assertEquals

class MembershipUpdateTest {

    private val event = anEvent().copy(members = MemberCounts(3, 1), openUntil = OpenUntil(ts("2026-07-28T18:00:00Z")))

    @Test
    fun `the name, the members and the open-until date follow the server`() {
        val counts = MemberCounts(4, 2, 1)
        val until = OpenUntil(ts("2026-08-04T18:00:00Z"))
        assertEquals(
            MembershipUpdate.Changed(event.copy(name = name("Lake week"), members = counts, openUntil = until)),
            MembershipUpdate.of(event, event.copy(name = name("Lake week"), members = counts, openUntil = until)),
        )
    }

    @Test
    fun `what the server did not serve keeps the last known`() {
        assertEquals(
            MembershipUpdate.Unchanged,
            MembershipUpdate.of(event, event.copy(members = null, openUntil = null)),
        )
    }
}
