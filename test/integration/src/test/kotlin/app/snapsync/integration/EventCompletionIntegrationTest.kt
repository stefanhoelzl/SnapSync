package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * The end of an event's life over the REAL composition (decision record `changes/back-dated-events`): the end-of-wake
 * step settles this device's share and reads the event's state, and the app never ends a membership on its own — a
 * finished event stays joined, a deleted one is shown as over — while a leave the backend never heard of is delivered
 * by a later wake. `EventCompletionTest` covers the verdicts in isolation; these prove the wiring.
 *
 * The window is short (five days) so a moment exists after its end and before the event's deadline.
 */
class EventCompletionIntegrationTest {

    @Test
    @Verifies(spec = "event-lifetime", requirement = "A member's share is settled only after their own range has ended")
    fun the_only_member_settles_its_share_and_stays_in_the_finished_event() = behaviour(startAt = DURING) {
        val event = given {
            extensionUploadsOnly()
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also { afterTheEnd() }
        }

        // The first upload cycle after the end settles the share — on this host the operator plays the uploader.
        whenever { cycle() }
        then { event<WorldEvent.ManifestPublished> { it.event == event && it.final } } // the share is settled after the end

        whenever { os.foreground() }
        then {
            never<WorldEvent.UiChanged> { it.ui.layer !is Layer.Joined } // a finished event never ends a membership
            never<WorldEvent.MemberLeft>() // and the backend heard of no leave
            settled { true }
        }
    }

    @Test
    @Verifies(spec = "event-lifetime", requirement = "The date range closes nothing")
    fun a_member_still_settling_keeps_this_member_in_the_event() = behaviour(startAt = DURING) {
        given {
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END)
            // Another member who has not settled its share (a manifest without the flag).
            fellowMember(DEV_F, "FQ")
            step("its photo's download started", act = { os.foreground() }, until = {
                event<WorldEvent.DownloadStarted>()
                settled { true }
            })
            step("it arrived", act = { downloadSession.finish() }, until = {
                event<WorldEvent.LibraryAssetCreated>()
                settled { true }
            })
            afterTheEnd()
        }

        whenever { os.foreground() }
        then {
            // an event still being shared never ends a membership
            never<WorldEvent.UiChanged> { it.ui.layer !is Layer.Joined }
            settled { true }
        }
    }

    @Test
    @Verifies(spec = "event-lifetime", requirement = "Deletion is never announced and never touches anyone's library")
    fun a_completed_event_is_kept_as_deleted_from_a_background_wake() = behaviour(startAt = DURING) {
        val event = given {
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also { event ->
                fellowMember(DEV_F, "FQ")
                afterTheEnd()
                // The clock ran out while this member was away: the sweep completed the event.
                step("the sweep completed it", act = { backend.complete(event) }, until = {
                    event<WorldEvent.EventDeleted> { it.event == event }
                })
            }
        }

        whenever { os.silentPush(event) }
        then {
            event<WorldEvent.CompletionReleased>()
            never<WorldEvent.UiChanged> { it.ui.layer !is Layer.Joined } // a deleted event is never left on its own
            never<WorldEvent.MemberLeft>() // the backend heard of no leave
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest",
        scenario = "A leave made offline still counts",
    )
    fun a_leave_made_offline_reaches_the_backend_on_a_later_wake() = behaviour {
        val event = given {
            joinedEvent().also {
                step("the backend unreachable", act = { backend.offline(true) }, until = {})
            }
        }

        whenever { tap(UiIntent.LeaveEvent) }
        then {
            screen<Layer.CreateEvent>()
            never<WorldEvent.MemberLeft>() // the backend was unreachable
            settled { true }
        }

        // The recorded leave is delivered without the member doing anything.
        whenever {
            backend.offline(false)
            os.foreground()
        }
        then { event<WorldEvent.MemberLeft> { it.event == event } }
    }

    /** The member puts the phone away until after the short event's end, and whatever the app does about it is done. */
    private suspend fun Given.afterTheEnd() = step("away until after the end", act = {
        os.background()
        advanceTo(AFTER_THE_END)
    }, until = { settled { true } })

    private companion object {
        const val SHORT_START = "2026-05-15T00:00:00"
        const val SHORT_END = "2026-05-20T00:00:00"

        /** Inside [SHORT_START]..[SHORT_END]: the event is running. */
        const val DURING = "2026-05-16T12:00:00Z"

        /** After [SHORT_END], and long before the event's deadline (30 days after its start). */
        const val AFTER_THE_END = "2026-05-22T00:00:00Z"
    }
}
