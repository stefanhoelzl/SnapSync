package app.snapsync.services.config

import app.snapsync.model.Direction
import app.snapsync.services.ledger.db.SelectMembership
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The membership row's mapping (capability `join-event`). The table's `CHECK` keeps the two member counts set or unset
 * together; a row read from anywhere else that broke it reads as no counts, never as a guessed half.
 */
class MembershipRowsTest {

    @Test
    fun `half a member count reads as no counts`() {
        val row = SelectMembership(
            eventId = "E",
            name = "Anna's Birthday",
            minPhotoDate = "2026-06-10T00:00:00Z",
            startsAt = "2026-06-10T00:00:00Z",
            endsAt = "2026-06-20T00:00:00Z",
            maxPhotoDate = "2026-06-20T00:00:00Z",
            deletesAt = "2026-07-20T00:00:00Z",
            direction = Direction.Both,
            saveToAlbum = false,
            closed = false,
            membersActive = 3,
            membersSettled = null,
            membersWaiting = null,
            keyId = null,
            openUntil = null,
            deleted = false,
        )
        assertNull(row.toConfig().members)
    }
}
