package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ShareCount
import kotlin.test.Test

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
    @Verifies(
        spec = "photo-sharing",
        requirement = "Only photos inside the range the member chose are shared",
        scenario = "A photo before the event inside the member's range is shared",
    )
    fun photos_from_before_the_start_wait_for_it_and_are_shared_once_it_has_passed() = behaviour(
        startAt = BEFORE_THE_START,
    ) {
        given {
            extensionUploadsOnly()
            // The member shares from the evening before the event, and took a photo then.
            joinedEvent(Choices(cutoff = EVENING_BEFORE), startsAt = START, endsAt = END)
            photoTaken("EVE", date = TAKEN_THE_EVENING_BEFORE)
        }

        // Nothing is uploaded before the event starts.
        whenever { uploadAll() }
        then {
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.BytesStored>()
        }

        // The photo from the evening before is shared once it starts.
        whenever {
            advanceTo(DURING)
            uploadAll()
        }
        then { event<WorldEvent.BytesStored> { it.asset == "EVE" } }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Nothing is shared before the event starts",
        scenario = "Sharing begins after the start",
    )
    fun once_the_start_has_passed_opening_the_app_shares_the_range() = behaviour(startAt = BEFORE_THE_START) {
        // No wake at the start: the photo waits for an ordinary occasion — here the member opening the app.
        given {
            joinedEvent(Choices(cutoff = EVENING_BEFORE), startsAt = START, endsAt = END)
            photoTaken("EVE", date = TAKEN_THE_EVENING_BEFORE)
        }

        whenever { os.foreground() }
        then { never<WorldEvent.SessionTransferCreated>() }

        whenever {
            os.background()
            advanceTo(DURING)
            os.foreground()
        }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("EVE") } }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Only photos inside the range the member chose are shared",
        scenario = "A photo after the event inside the member's range is shared",
    )
    fun a_photo_after_the_event_inside_the_members_range_is_shared() = behaviour(startAt = DURING) {
        given {
            extensionUploadsOnly()
            joinedEvent(Choices(until = TWO_DAYS_AFTER), startsAt = START, endsAt = END)
            step("past the member's range", act = { advanceTo(AFTER_THE_RANGE) }, until = { settled { true } })
            photoTaken("NEXTDAY", date = TAKEN_THE_DAY_AFTER)
            photoTaken("PAST", date = TAKEN_AFTER_THE_RANGE) // the control: the member's own end still holds
        }

        whenever { uploadAll() }
        then {
            event<WorldEvent.BytesStored> { it.asset == "NEXTDAY" }
            never<WorldEvent.BytesStored> { it.asset == "PAST" }
            never<WorldEvent.ManifestPublished> { "PAST" in it.added }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "What the member is told is shared is exactly what is shared",
        scenario = "Before the start the preview counts the photos that wait for it",
    )
    fun before_the_start_the_preview_counts_the_photos_that_wait_for_it() = behaviour(startAt = BEFORE_THE_START) {
        given {
            extensionUploadsOnly()
            photoTaken("EVE", date = TAKEN_THE_EVENING_BEFORE)
            createdEvent(startsAt = START, endsAt = END)
        }

        // The preview answers what WILL be shared: the photo from the evening before, though it waits for the start.
        whenever { setRange(Choices(cutoff = EVENING_BEFORE)) }
        then { screen<Layer.JoiningEvent> { it.range?.shareCount == ShareCount.Ready(1) } }

        whenever {
            confirmJoin()
            uploadAll()
        }
        then {
            screen<Layer.Joined>()
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.BytesStored>()
        }

        // Once the event has started, the counted photo is the one uploaded.
        whenever {
            advanceTo(DURING)
            uploadAll()
        }
        then { event<WorldEvent.BytesStored> { it.asset == "EVE" } }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "A member's share is settled only after their own range has ended",
        scenario = "A range that ends after the event keeps it unfinished",
    )
    fun a_range_ending_after_the_event_is_settled_at_its_own_end() = behaviour(startAt = DURING) {
        given {
            extensionUploadsOnly()
            joinedEvent(Choices(until = TWO_DAYS_AFTER), startsAt = START, endsAt = END)
        }

        whenever {
            advanceTo(AFTER_THE_EVENT)
            cycle()
        }
        then { never<WorldEvent.ManifestPublished> { it.final } } // the member's range has not ended yet

        whenever {
            advanceTo(AFTER_THE_RANGE)
            cycle()
        }
        then { event<WorldEvent.ManifestPublished> { it.final } } // settled once the member's own range has ended
    }

    @Test
    @Verifies(spec = "event-lifetime", requirement = "A member's share is settled only after their own range has ended")
    fun a_range_ending_before_the_event_is_settled_before_the_event_ends() = behaviour(startAt = DURING) {
        given {
            extensionUploadsOnly()
            joinedEvent(Choices(direction = Direction.UploadOnly, until = EARLY_END), startsAt = START, endsAt = END)
        }

        // Settled at the member's own end, while the event still runs.
        whenever {
            advanceTo(AFTER_THE_EARLY_END)
            cycle()
        }
        then { event<WorldEvent.ManifestPublished> { it.final } }
    }

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

        const val TAKEN_THE_DAY_AFTER = "2026-05-21T10:00:00Z"
        const val TAKEN_AFTER_THE_RANGE = "2026-05-22T06:00:00Z"
    }
}
