package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.step
import kotlin.test.Test

/**
 * **The ledger is the current membership's share set**, over the real stack driven through the control protocol:
 * a join loads it from the device's stored-file listing, a leave clears it, and a switch does both — without
 * re-uploading anything the backend already holds, and without a failed listing ever blocking a join.
 *
 * The ledger itself is the app's own bookkeeping, so each test reads its observable twin: the upload jobs the
 * operating system was asked for, the objects the backend holds, and the joined screen's health.
 */
class ShareSetIntegrationTest {

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "A photo already in an event is not uploaded again for it",
        scenario = "Rejoining shares without re-uploading",
    )
    fun leave_then_rejoin_the_same_event_re_uploads_nothing() = behaviour {
        val event = given {
            extensionUploadsOnly()
            joinedEvent().also {
                photos("A")
                uploaded("A")
                left()
            }
        }

        // Rejoin through the event's link, as a member re-scanning the QR does.
        whenever { openLink(inviteLink(event)) }
        then { screen<Layer.JoiningEvent> { it.phase.step == JoinPhase.Detailed.Step.Ready } }
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            event<WorldEvent.MemberJoined>()
            settled { true }
        }

        // The join loaded the stored photo back as done: the screen reads settled with the photo still in the library…
        whenever { os.foreground() }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }

        // …and nothing the backend already holds is uploaded again.
        whenever { cycle() }
        then { never<WorldEvent.UploadJobCreated>() }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "A photo already in an event is not uploaded again for it",
        scenario = "A photo in two events is shared to each",
    )
    fun a_switch_starts_the_new_events_share_set_afresh_and_uploads_for_it() = behaviour {
        val next = given {
            extensionUploadsOnly()
            registeredEvent(name = "Next").also { // E2, to switch to
                joinedEvent() // E1
                photos("A")
                uploaded("A")
                // Work the first membership recorded but never finished: it belongs to E1's share set only.
                photos("B")
                step("B requested", act = { cycle() }, until = {
                    event<WorldEvent.UploadJobCreated> { it.job == primaryJob("B") }
                    settled { true }
                })
            }
        }

        // Each event holds its own bytes (change `per-event-storage-layout`): the new share set starts empty, so A —
        // stored for E1 — and E1's unfinished B are both uploaded for E2, one new job each.
        whenever { switchTo(next) }
        then {
            screen<Layer.Joined> { it.membership.eventId == next }
            never<WorldEvent.EventDeleted>() // E1 still holds what was stored for it
        }
        whenever { cycle() }
        then {
            times(2) { event<WorldEvent.UploadJobCreated>() }
            event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") }
            event<WorldEvent.UploadJobCreated> { it.job == primaryJob("B") }
        }
    }

    @Test
    @Verifies(spec = "photo-sharing", requirement = "A photo already in an event is not uploaded again for it")
    fun switching_away_and_back_re_uploads_nothing_the_event_already_holds() = behaviour {
        val first = given {
            extensionUploadsOnly()
            val next = registeredEvent(name = "Next")
            val first = joinedEvent()
            photos("A")
            uploaded("A")
            switchedTo(next)
            uploaded("A")
            first
        }

        whenever { switchTo(first) }
        then { screen<Layer.Joined> { it.membership.eventId == first } }

        // A is already in E1: nothing is uploaded again on the way back.
        whenever { cycle() }
        then { never<WorldEvent.UploadJobCreated>() }
    }

    /**
     * RESHAPED (design D8): the old test compared the ledger's rows around a re-provision of the joined event. The
     * gesture that re-provisions is a member re-scanning their own event's link, and what a reset ledger would do is
     * observable: the in-flight work would be requested again.
     */
    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Leaving or switching stops every transfer for the old event",
        scenario = "Rescanning the joined event changes nothing",
    )
    @Verifies(spec = "invite-link", requirement = "Reopening the current event's invite changes nothing")
    fun re_scanning_your_own_events_link_creates_no_new_upload_job_and_re_uploads_nothing() = behaviour {
        val event = given {
            extensionUploadsOnly()
            joinedEvent().also {
                photos("A")
                step("A requested: work in flight", act = { cycle() }, until = {
                    times(1) { event<WorldEvent.UploadJobCreated>() }
                    settled { true }
                })
            }
        }

        whenever { openLink(inviteLink(event)) }
        then {
            never<WorldEvent.MemberJoined>()
            never<WorldEvent.MemberLeft>()
            neverScreen<Layer.Joined> { it.membership.eventId != event }
        }

        // A re-scan loads nothing, so the in-flight work is not requested again, and nothing is re-uploaded.
        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.BytesStored>()
            settled { it.state.inFlight?.jobs == listOf(primaryJob("A")) }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "A photo already in an event is not uploaded again for it",
        scenario = "A reinstalled app keeps its photos",
    )
    fun a_reinstalled_app_rejoining_uploads_nothing_again_and_offers_nothing_twice() = behaviour {
        val link = given {
            extensionUploadsOnly()
            val event = joinedEvent()
            photos("A")
            uploaded("A")
            inviteFor(event).also {
                step("reinstalled", act = { host.reinstall() }, until = { settled { true } })
            }
        }

        // Rejoin through the event's link, as the member re-scanning the QR after the reinstall does.
        whenever { openLink(link) }
        then { screen<Layer.JoiningEvent> { it.phase.step == JoinPhase.Detailed.Step.Ready } }
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            event<WorldEvent.MemberJoined>()
            settled { true }
        }

        // The photo is still in the library, and neither uploader sends it again: the event keeps offering the one copy.
        whenever {
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
            cycle()
        }
        then {
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.BytesStored>()
            never<WorldEvent.UnionChanged> { it.asset == "A" }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "A photo already in an event is not uploaded again for it",
        scenario = "An offline join may upload again, never duplicate",
    )
    fun a_join_whose_listing_fails_still_joins_and_uploads() = behaviour {
        val next = given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            uploaded("A")
            left()
            step("the listing failing", act = { backend.failListing(true) }, until = {})
            createdEvent(name = "Next")
        }

        // The failed listing blocked nothing.
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined> { it.membership.eventId == next }
            settled { true }
        }

        // The ledger started empty rather than stale — and the cost of the failed load: the photo is uploaded
        // again, idempotently.
        whenever {
            backend.failListing(false)
            cycle()
        }
        then {
            times(1) { event<WorldEvent.UploadJobCreated>() }
            settled { primaryJob("A") in it.state.inFlight?.jobs.orEmpty() } // the photo is requested again
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The member leaves the joined event, and the backend records it. */
    private suspend fun Given.left() = step("left", act = { tap(UiIntent.LeaveEvent) }, until = {
        event<WorldEvent.MemberLeft>()
        settled { true }
    })

    /** A switch to [eventId] while joined: done ([switchTo]), and whatever it started settled. */
    private suspend fun Given.switchedTo(eventId: String) = step("switched to $eventId", act = {
        switchTo(eventId)
    }, until = {
        screen<Layer.Joined> { it.membership.eventId == eventId }
        settled { true }
    })

    /** [eventId]'s link opened while joined, the switch confirmed once its gate is ready, then the regular join. */
    private suspend fun Acts.switchTo(eventId: String) {
        openLink(inviteLink(eventId))
        awaitScreen("the switch ready") { ui ->
            (ui.layer as? Layer.Joined)?.pendingSwitch?.phase?.step == JoinPhase.Detailed.Step.Ready
        }
        tap(UiIntent.ConfirmSwitch)
        awaitScreen("$eventId's join gate") { ui ->
            (ui.layer as? Layer.JoiningEvent)?.let {
                it.eventId == eventId && it.phase.step == JoinPhase.Detailed.Step.Ready
            } == true
        }
        confirmJoin()
    }
}
