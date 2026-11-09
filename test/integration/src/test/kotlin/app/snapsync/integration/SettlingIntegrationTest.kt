package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **A share settles only on a look taken since the member's range ended** (capability `event-lifetime`, change
 * `settling-needs-a-real-look`), over the real composition: a look that cannot read the library settles nothing, a
 * member who shares nothing settles at the end without one, a selection read since the end settles a limited-access
 * member, and a rejoin after the end settles again.
 *
 * The short event runs from 15 to 20 May; the clock starts during it.
 */
@Verifies(spec = "event-lifetime", requirement = "A member's share is settled only after their own range has ended")
class SettlingIntegrationTest {

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "A member's share is settled only after their own range has ended",
        scenario = "A look that fails after the end settles nothing",
    )
    fun a_library_that_cannot_be_read_after_the_end_settles_nothing() = behaviour(startAt = DURING) {
        val event = given {
            extensionUploadsOnly()
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also {
                photoTaken("P", date = TAKEN_DURING)
                laterInThePocket(AFTER_THE_END)
            }
        }

        // The first look after the end cannot read the library: it is no look, and settles nothing.
        whenever {
            library.failNextEnumeration()
            cycle()
        }
        then {
            never<WorldEvent.ManifestPublished> { it.final }
            settled { true }
        }

        // The next look reads it: the photo from the event is shared, and the share settles.
        whenever { cycle() }
        then { event<WorldEvent.ManifestPublished> { it.event == event && "P" in it.added && it.final } }
    }

    @Test
    fun a_settled_share_stays_settled_when_a_later_look_fails() = behaviour(startAt = DURING) {
        given {
            extensionUploadsOnly()
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END)
            photoTaken("P", date = TAKEN_DURING)
            laterInThePocket(AFTER_THE_END)
            step("the share settled", act = { cycle() }, until = {
                event<WorldEvent.ManifestPublished> { "P" in it.added && it.final }
                settled { true }
            })
        }

        whenever {
            library.failNextEnumeration()
            cycle()
        }
        then {
            never<WorldEvent.ManifestPublished> { !it.final }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "A member's share is settled only after their own range has ended",
        scenario = "A look that fails after the end settles nothing",
    )
    fun a_walk_that_finds_the_library_unreadable_after_the_end_settles_nothing() = behaviour(startAt = DURING) {
        given {
            appUploadsOnly()
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also {
                photoTaken("P", date = TAKEN_DURING)
                laterInThePocket(AFTER_THE_END)
                step("the library's walks held", act = { library.holdEnumeration(on = true) }, until = {})
            }
        }

        // A wake's walk starts under full access, and is parked reading the library.
        whenever { heartbeat() }
        then { event<WorldEvent.LibraryEnumerationHeld>() }

        // Access is withdrawn before the walk reads: it finds the library unreadable — no look.
        whenever {
            library.permission(PhotoGrant.DENIED)
            library.holdEnumeration(on = false)
        }
        then {
            never<WorldEvent.ManifestPublished> { it.final }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "A member's share is settled only after their own range has ended",
    )
    fun a_walk_begun_before_the_end_settles_nothing_however_late_it_publishes() = behaviour(startAt = DURING) {
        given {
            appUploadsOnly()
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also {
                laterInThePocket(JUST_BEFORE_THE_END)
                step("the library's walks held", act = { library.holdEnumeration(on = true) }, until = {})
            }
        }

        // A photo taken just before the end changes the library: a wake's walk reads it afresh, and is parked reading.
        whenever {
            library.add("Q", date = JUST_BEFORE_THE_END)
            heartbeat()
        }
        then { event<WorldEvent.LibraryEnumerationHeld>() }

        // It reads only after the end: what it saw began before it, so its publish settles nothing — the wake's end then
        // looks again, since the end, and that look settles the share.
        whenever {
            advanceTo(SOON_AFTER_THE_END)
            library.holdEnumeration(on = false)
        }
        then {
            never<WorldEvent.ManifestPublished> { "Q" in it.added && it.final }
            event<WorldEvent.ManifestPublished> { "Q" in it.added && !it.final }
            event<WorldEvent.ManifestPublished> { it.final }
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "A member's share is settled only after their own range has ended",
        scenario = "A member who shares nothing settles at the end",
    )
    fun a_member_who_shares_nothing_settles_at_the_end_without_a_look() = behaviour(startAt = DURING) {
        val event = given {
            extensionUploadsOnly()
            joinedEvent(Choices(direction = Direction.DownloadOnly), startsAt = SHORT_START, endsAt = SHORT_END)
                .also { laterInThePocket(AFTER_THE_END) }
        }

        whenever {
            cycle()
            heartbeat()
        }
        then { event<WorldEvent.ManifestPublished> { it.event == event && it.final && it.added.isEmpty() } }
    }

    @Test
    fun a_limited_access_member_settles_on_the_selection_read_since_the_end() = behaviour(startAt = DURING) {
        val event = given {
            photoAccess(PhotoGrant.LIMITED)
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also {
                photoTaken("A", date = TAKEN_DURING)
                selected("A")
                step("A shared", act = { uploadSession.complete() }, until = {
                    event<WorldEvent.BytesStored> { it.asset == "A" }
                    settled { true }
                })
                laterInThePocket(AFTER_THE_END)
            }
        }

        // A wake ends: the selection the app holds is its look, read since the end.
        whenever { heartbeat() }
        then { event<WorldEvent.ManifestPublished> { it.event == event && it.final } }
    }

    @Test
    fun a_limited_access_member_whose_selection_is_unread_settles_once_it_is_read() = behaviour(startAt = DURING) {
        val event = given {
            photoAccess(PhotoGrant.LIMITED)
            // The selection observer has not emitted yet: the app holds no selection, which is not an empty one.
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also {
                photoTaken("A", date = TAKEN_DURING)
                laterInThePocket(AFTER_THE_END)
            }
        }

        // The wake ends with the selection unread: no look, so nothing settles, and nothing throws.
        whenever { heartbeat() }
        then {
            never<WorldEvent.ManifestPublished> { it.final }
            settled { true }
        }

        // The selection arrives (the observer's first emission): it is read since the end, and the share settles on it.
        whenever { library.changeSelection(listOf("A")) }
        then { event<WorldEvent.ManifestPublished> { it.event == event && "A" in it.added && it.final } }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "A member's share is settled only after their own range has ended",
        scenario = "Rejoining after the end settles again",
    )
    fun a_member_who_rejoins_after_the_end_settles_again() = behaviour(startAt = DURING) {
        val event = given {
            extensionUploadsOnly()
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also {
                laterInThePocket(AFTER_THE_END)
                step("the share settled", act = { cycle() }, until = {
                    event<WorldEvent.ManifestPublished> { published -> published.event == it && published.final }
                    settled { true }
                })
                left()
                step("the listing failing", act = { backend.failListing(true) }, until = {})
                gateOpenOn(it)
            }
        }

        // The rejoin cannot learn what the event holds: the device keeps what it remembered of it.
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined> { it.membership.eventId == event }
            settled { true }
        }

        // The backend treats the rejoin as a new share, unsettled: the device settles it again.
        whenever {
            backend.failListing(false)
            cycle()
        }
        then { event<WorldEvent.ManifestPublished> { it.event == event && it.final } }
    }

    private companion object {
        const val SHORT_START = "2026-05-15T00:00:00"
        const val SHORT_END = "2026-05-20T00:00:00"

        /** Inside [SHORT_START]..[SHORT_END]: the event is running. */
        const val DURING = "2026-05-16T12:00:00Z"
        const val TAKEN_DURING = "2026-05-16T10:00:00Z"

        /** Shortly before and after [SHORT_END] (local midnight) in every zone from UTC-12 to UTC+4. */
        const val JUST_BEFORE_THE_END = "2026-05-19T20:00:00Z"
        const val SOON_AFTER_THE_END = "2026-05-20T14:00:00Z"

        /** After [SHORT_END], and long before the event's deadline. */
        const val AFTER_THE_END = "2026-05-22T00:00:00Z"
    }
}
