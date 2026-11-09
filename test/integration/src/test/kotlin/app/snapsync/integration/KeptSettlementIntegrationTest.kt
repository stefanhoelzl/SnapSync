package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A settled share is never taken back by a look that fails** (capability `event-lifetime`, change
 * `settling-needs-a-real-look`): once the range has ended and a settled share was sent, a later look that cannot read
 * the library proves nothing — and sends nothing that would unsettle it.
 *
 * The short event runs from 15 to 20 May; the clock starts during it.
 */
@Verifies(spec = "event-lifetime", requirement = "A member's share is settled only after their own range has ended")
class KeptSettlementIntegrationTest {

    @Test
    fun a_look_that_cannot_read_the_library_after_the_share_settled_keeps_it_settled() = behaviour(startAt = DURING) {
        val event = given {
            appUploadsOnly()
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also { event ->
                photoTaken("P", date = TAKEN_DURING)
                laterInThePocket(AFTER_THE_END)
                step("the share settled", act = { heartbeat() }, until = {
                    event<WorldEvent.ManifestPublished> { it.event == event && it.final }
                    settled { true }
                })
                step("the library's walks held", act = { library.holdEnumeration(on = true) }, until = {})
            }
        }

        // A photo taken since changes the library: a wake's walk reads it afresh, and is parked reading.
        whenever {
            library.add("LATER", date = AFTER_THE_END)
            heartbeat()
        }
        then { event<WorldEvent.LibraryEnumerationHeld>() }

        // Access is withdrawn before the walk reads: it finds the library unreadable — no look.
        whenever {
            library.permission(PhotoGrant.DENIED)
            library.holdEnumeration(on = false)
        }
        then {
            never<WorldEvent.ManifestPublished> { it.event == event && !it.final }
            settled { true }
        }
    }

    private companion object {
        const val SHORT_START = "2026-05-15T00:00:00"
        const val SHORT_END = "2026-05-20T00:00:00"

        /** Inside [SHORT_START]..[SHORT_END]: the event is running. */
        const val DURING = "2026-05-16T12:00:00Z"
        const val TAKEN_DURING = "2026-05-16T10:00:00Z"

        /** After [SHORT_END], and long before the event's deadline. */
        const val AFTER_THE_END = "2026-05-22T00:00:00Z"
    }
}
