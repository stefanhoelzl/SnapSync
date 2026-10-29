package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **Joining after the end, and an event that can no longer be joined**, over the real composition: the end of an
 * event's range closes nothing — a guest who scans a week late joins with the event's dates and shares what they took
 * in them, a back-dated party's guests join the next day — while an event whose photos were deleted is refused on the
 * join screen, and only on the service's word, never on a lost connection (capabilities `join-event`,
 * `event-lifetime`).
 *
 * What is shared is read off the operating system's upload jobs: only the extension uploads here, so a photo the
 * membership admits is a job its cycle creates.
 */
class LateJoinIntegrationTest {

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "A late guest can still join",
        scenario = "A guest scans days after the party",
    )
    @Verifies(
        spec = "event-lifetime",
        requirement = "The date range closes nothing",
        scenario = "A late guest still joins",
    )
    fun a_guest_scanning_a_week_after_the_end_joins_with_the_events_dates_and_shares_from_them() = behaviour {
        val link = given {
            extensionUploadsOnly()
            val link = inviteFor(registeredEvent())
            photos("A") // taken on the event's day
            laterInThePocket(A_WEEK_AFTER_THE_END)
            inFront()
            link
        }

        whenever { openLink(link) }
        then {
            screen<Layer.JoiningEvent> { gate ->
                val range = gate.range
                gate.phase.ready() && range != null && range.from == range.windowStart && range.until == range.windowEnd
            }
        }

        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            settled { true }
        }

        whenever { cycle() }
        then { event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") } }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "A late guest can still join",
        scenario = "A guest of a back-dated event",
    )
    @Verifies(
        spec = "event-lifetime",
        requirement = "An event stays open until the date its host chose",
        scenario = "Guests join a party that is over",
    )
    fun a_guest_of_a_party_already_over_joins_the_next_day_and_shares_and_receives() = behaviour {
        val link = given { inviteFor(backDatedParty()) }

        whenever { openLink(link) }
        then { screen<Layer.JoiningEvent> { it.phase.ready() } }

        // Receiving starts with the join: the fellow guest's photo is on its way.
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            event<WorldEvent.DownloadStarted>()
            settled { true }
        }

        whenever { cycle() }
        then { event<WorldEvent.UploadJobCreated> { it.job == primaryJob(AT_THE_PARTY) } }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "An event stays open until the date its host chose",
        scenario = "The range still bounds the photos",
    )
    fun a_photo_taken_after_a_back_dated_party_is_not_shared() = behaviour {
        given {
            gateOpenOn(backDatedParty())
            step("joined", act = { confirmJoin() }, until = {
                screen<Layer.Joined>()
                settled { true }
            })
        }

        whenever { cycle() }
        then {
            event<WorldEvent.UploadJobCreated> { it.job == primaryJob(AT_THE_PARTY) }
            never<WorldEvent.UploadJobCreated> { it.job == primaryJob(THE_DAY_AFTER) }
        }
    }

    // ── an event whose photos were deleted ──────────────────────────────────────────────────────────────

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "An event whose photos were deleted cannot be joined",
        scenario = "A guest scans after the deletion",
    )
    fun an_invite_to_a_deleted_event_says_it_can_no_longer_be_joined() = behaviour {
        val link = given {
            val event = registeredEvent()
            val link = inviteFor(event)
            step("its photos deleted", act = { backend.complete(event) }, until = {})
            link
        }

        whenever { openLink(link) }
        then { screen<Layer.JoiningEvent> { it.phase == JoinPhase.Closed } }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "An event whose photos were deleted cannot be joined",
        scenario = "The photos are deleted while the join screen is open",
    )
    fun a_join_confirmed_after_the_deletion_joins_nothing_and_says_so() = behaviour {
        given {
            val event = registeredEvent()
            gateOpenOn(event)
            step("its photos deleted", act = { backend.complete(event) }, until = {})
        }

        whenever { confirmJoin() }
        then {
            screen<Layer.JoiningEvent> { it.phase == JoinPhase.Closed }
            neverScreen<Layer.Joined>()
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "An event whose photos were deleted cannot be joined",
        scenario = "A member does not lose their event to a deleted event's invite",
    )
    fun a_member_opening_a_deleted_events_invite_stays_in_their_event() = behaviour {
        val link = given {
            val other = registeredEvent(name = "Ski Trip")
            val link = inviteFor(other)
            joinedEvent(name = "Ana's 30th")
            step("the other event's photos deleted", act = { backend.complete(other) }, until = {})
            link
        }

        whenever { openLink(link) }
        then {
            screen<Layer.Joined> { it.membership.name == "Ana's 30th" && it.pendingSwitch?.phase == JoinPhase.Closed }
            never<WorldEvent.BackendReceived> { it.operation == "leaveEvent" }
            neverScreen<Layer.JoiningEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "An event whose photos were deleted cannot be joined",
        scenario = "Offline is not \"can no longer be joined\"",
    )
    fun a_join_that_cannot_reach_the_service_is_retryable_not_closed() = behaviour {
        given {
            gateOpenOn(registeredEvent())
            step("the service unreachable", act = { backend.offline(true) }, until = {})
        }

        whenever { confirmJoin() }
        then {
            screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.CommitFailed }
            neverScreen<Layer.JoiningEvent> { it.phase == JoinPhase.Closed }
        }
    }

    // ── cancelling ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Joining happens only on confirmation and needs a connection",
        scenario = "Cancel abandons the invite",
    )
    fun cancelling_the_join_screen_joins_nothing_and_shows_the_create_screen() = behaviour {
        given {
            photos("A")
            gateOpenOn(registeredEvent())
        }

        whenever { tap(UiIntent.CancelJoin) }
        then {
            screen<Layer.CreateEvent>()
            never<WorldEvent.BackendReceived> { it.operation == "joinEvent" }
        }

        // Nothing is shared either: no membership, so no upload cycle has anything to send.
        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.SessionTransferCreated>()
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * A party on a weekend already over, created the next Monday and kept open for a week; a fellow guest shared a
     * photo of it. This device took one photo at the party and one the day after; the next day it is in front again.
     * Answers the party's id.
     */
    private suspend fun Given.backDatedParty(): String {
        extensionUploadsOnly()
        val event = registeredEvent(startsAt = PARTY_START, endsAt = PARTY_END)
        step("a fellow guest shared a photo", act = {
            publicBackend.foreignDevice(DEV_F, listOf("FQ"), event = event)
        }, until = {})
        step("photos taken at the party and the day after", act = {
            library.add(AT_THE_PARTY, date = AT_THE_PARTY_DATE)
            library.add(THE_DAY_AFTER, date = THE_DAY_AFTER_DATE)
        }, until = { settled { true } })
        laterInThePocket(THE_NEXT_DAY)
        inFront()
        return event
    }

    /** The join gate's details are loaded and it offers Join. */
    private fun JoinPhase.ready() = (this as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready

    private companion object {
        /** A week after the default window's end ([Defaults.WINDOW_END], 14 June); long before its photos may be deleted. */
        const val A_WEEK_AFTER_THE_END = "2026-06-21T12:00:00Z"

        /** A party on the weekend before every behaviour's clock starts (Monday 1 June). */
        const val PARTY_START = "2026-05-30T18:00:00"
        const val PARTY_END = "2026-05-31T20:00:00"
        const val AT_THE_PARTY = "PARTY"
        const val AT_THE_PARTY_DATE = "2026-05-30T21:00:00Z"
        const val THE_DAY_AFTER = "AFTER"

        /** After the party's end, before the week it is kept open has passed. */
        const val THE_DAY_AFTER_DATE = "2026-06-01T09:00:00Z"

        /** The day after the party was created. */
        const val THE_NEXT_DAY = "2026-06-02T12:00:00Z"
    }
}
