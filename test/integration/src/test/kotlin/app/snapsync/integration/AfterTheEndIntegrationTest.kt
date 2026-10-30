package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **An event's end closes nothing on the device** (capability `event-lifetime`), over the real composed stack: a
 * photo still waiting when the range ends, one taken on its last evening, and one selected after it still reach the
 * event; and an event whose photos were deleted stays on screen as over, its received photos kept.
 *
 * The short event runs from 25 to 29 May; the clock starts during it.
 */
class AfterTheEndIntegrationTest {

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "The date range closes nothing",
        scenario = "Sync continues after the end",
    )
    fun a_photo_still_waiting_when_the_range_ends_uploads_after_it() = behaviour(startAt = DURING) {
        given {
            extensionUploadsOnly()
            joinedEvent(startsAt = START, endsAt = END)
            photoTaken("P", date = TAKEN_DURING)
        }

        whenever {
            advanceTo(AFTER_THE_END)
            uploadAll()
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "P" }
            event<WorldEvent.UnionChanged> { it.asset == "P" && it.gained }
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "A member's share is settled only after their own range has ended",
        scenario = "A photo from the last evening",
    )
    fun a_photo_from_the_last_evening_is_shared_when_the_phone_comes_back() = behaviour(startAt = DURING) {
        given {
            extensionUploadsOnly()
            joinedEvent(startsAt = START, endsAt = END)
            step("the last evening", act = { advanceTo(LAST_EVENING_LATER) }, until = { settled { true } })
            photoTaken("LAST", date = LAST_EVENING)
        }

        // The phone stays off until the next day: no cycle until then.
        whenever {
            advanceTo(NEXT_DAY)
            uploadAll()
        }
        then {
            event<WorldEvent.ManifestPublished> { "LAST" in it.added }
            event<WorldEvent.BytesStored> { it.asset == "LAST" }
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "A member's share is settled only after their own range has ended",
        scenario = "Selecting more photos after the end",
    )
    fun a_photo_selected_after_the_end_is_shared() = behaviour(startAt = DURING) {
        given {
            photoAccess(PhotoGrant.LIMITED)
            joinedEvent(startsAt = START, endsAt = END)
            photoTaken("A", date = TAKEN_DURING)
            photoTaken("B", date = TAKEN_DURING)
            selected("A")
            step("A shared", act = { uploadSession.complete() }, until = {
                event<WorldEvent.BytesStored> { it.asset == "A" }
                settled { true }
            })
            laterInThePocket(AFTER_THE_END)
            inFront()
        }

        whenever { library.changeSelection(listOf("A", "B")) }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") } }

        whenever {
            uploadSession.complete()
            os.foreground()
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "B" }
            event<WorldEvent.UnionChanged> { it.asset == "B" && it.gained }
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "Deletion is never announced and never touches anyone's library",
        scenario = "A member opens the app after deletion",
    )
    fun after_deletion_the_app_says_the_event_is_over_and_the_received_photos_stay() = behaviour {
        val event = given {
            joinedEvent().also {
                received(DEV_F, "FQ")
                step("its photos deleted, the phone in a pocket", act = {
                    backend.complete(it)
                    os.background()
                }, until = { settled { true } })
            }
        }

        whenever {
            advanceTo(DAYS_LATER)
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.membership.eventId == event && it.deleted }
            neverScreen<Layer.CreateEvent>()
            never<WorldEvent.PushSent>()
            settled { it.state.libraryPhotos.orEmpty().any { photo -> photo.startsWith(imported("FQ")) } }
        }
    }

    private companion object {

        const val START = "2026-05-25T00:00:00"
        const val END = "2026-05-29T00:00:00"
        const val DURING = "2026-05-26T12:00:00Z"
        const val TAKEN_DURING = "2026-05-26T10:00:00Z"

        /** An hour before the range ends, and a moment after the photo. */
        const val LAST_EVENING = "2026-05-28T21:00:00Z"
        const val LAST_EVENING_LATER = "2026-05-28T21:15:00Z"
        const val NEXT_DAY = "2026-05-29T12:00:00Z"

        /** Two days past the end, before the event may be deleted. */
        const val AFTER_THE_END = "2026-05-31T00:00:00Z"

        /** Days after the default event's photos were deleted. */
        const val DAYS_LATER = "2026-06-05T12:00:00Z"
    }
}
