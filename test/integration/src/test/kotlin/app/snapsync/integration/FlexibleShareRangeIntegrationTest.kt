package app.snapsync.integration

import app.snapsync.control.Verifies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A member's range reaching outside the event's dates, over the REAL composition (decision record
 * `changes/flexible-share-range`): photos from before the event's start wait for it, and a member's share is settled
 * at the end of their OWN range — later than the event's when their range ends after it, earlier when it ends before.
 * The rules are pinned in isolation by `CutoffTest`, `EventConfigTest`, `CycleGateReadTest` and `EventCompletionTest`;
 * these prove the wiring.
 *
 * The event runs from 15 to 20 May.
 */
class FlexibleShareRangeIntegrationTest {

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Nothing is shared before the event starts",
        scenario = "Photos from before the start wait for it",
    )
    fun photos_from_before_the_start_wait_for_it_and_are_shared_once_it_has_passed() = rigTest {
        extensionUploadsOnly()
        advanceTo(BEFORE_THE_START)
        create(startsAt = START, endsAt = END)
        // The member shares from the evening before the event, and took a photo then.
        join("cutoff" to EVENING_BEFORE)
        addPhoto("EVE", date = TAKEN_THE_EVENING_BEFORE)

        uploadAll()
        assertTrue(objects().isEmpty(), "nothing is uploaded before the event starts")

        advanceTo(DURING)
        uploadAll()
        assertTrue(objects().any { it.startsWith("EVE") }, "the photo from the evening before is shared once it starts")
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "A member's share is settled only after their own range has ended",
        scenario = "A range that ends after the event keeps it unfinished",
    )
    fun a_range_ending_after_the_event_is_settled_at_its_own_end() = rigTest {
        extensionUploadsOnly()
        advanceTo(DURING)
        val event = createAndJoin("until" to TWO_DAYS_AFTER, startsAt = START, endsAt = END)

        advanceTo(AFTER_THE_EVENT)
        cycle()
        assertEquals(false, final(event), "the member's range has not ended yet")

        advanceTo(AFTER_THE_RANGE)
        cycle()
        assertEquals(true, final(event), "settled once the member's own range has ended")
    }

    @Test
    @Verifies(spec = "event-lifetime", requirement = "A member's share is settled only after their own range has ended")
    fun a_range_ending_before_the_event_is_settled_before_the_event_ends() = rigTest {
        extensionUploadsOnly()
        advanceTo(DURING)
        val event = createAndJoin("direction" to "upload", "until" to EARLY_END, startsAt = START, endsAt = END)

        advanceTo(AFTER_THE_EARLY_END)
        cycle()
        assertEquals(true, final(event), "settled at the member's own end, while the event still runs")
    }

    private suspend fun Rig.final(event: String): Boolean =
        checkNotNull(backend.membership(event).manifest).final

    private companion object {
        const val START = "2026-05-15T00:00:00"
        const val END = "2026-05-20T00:00:00"

        const val BEFORE_THE_START = "2026-05-14T22:00:00Z"

        // Hours ahead of the photo: the rig reads a `cutoff` in the host's own zone, which may run ahead of UTC.
        const val EVENING_BEFORE = "2026-05-14T06:00:00Z"
        const val TAKEN_THE_EVENING_BEFORE = "2026-05-14T19:30:00Z"
        const val DURING = "2026-05-16T12:00:00Z"

        const val EARLY_END = "2026-05-17T20:00:00Z"
        const val AFTER_THE_EARLY_END = "2026-05-18T09:00:00Z"

        const val AFTER_THE_EVENT = "2026-05-21T00:00:00Z"
        const val TWO_DAYS_AFTER = "2026-05-22T00:00:00Z"
        const val AFTER_THE_RANGE = "2026-05-22T12:00:00Z"
    }
}
