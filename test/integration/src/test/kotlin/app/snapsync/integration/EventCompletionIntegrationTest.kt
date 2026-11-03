package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.SettledEvent
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

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest",
        scenario = "Everyone has everything",
    )
    fun once_this_member_has_everything_after_the_end_the_event_is_finished() = behaviour(startAt = DURING) {
        val event = given {
            extensionUploadsOnly()
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also { event ->
                receivedFrom(DEV_F, "FQ", final = true)
                afterTheEnd()
                step("the fellow member holds everything", act = {
                    publicBackend.foreignDeviceReceived(
                        DEV_F,
                    )
                }, until = {
                    settled { it.of(event)?.finished == false } // this member has not said what it received yet
                })
            }
        }

        // The first wake after the end settles this member's share and tells how far it received: the event is finished,
        // so the next sweep deletes its photos without waiting 3 days.
        whenever {
            cycle()
            os.foreground()
        }
        then {
            event<WorldEvent.ManifestPublished> { it.event == event && it.final }
            event<WorldEvent.ReceivedReported> { it.event == event && it.device != DEV_F }
            settled { it.of(event)?.finished == true }
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "A member's share is settled only after their own range has ended",
        scenario = "A late guest's photos reopen the others",
    )
    fun a_late_guest_s_photos_keep_the_event_unfinished_until_they_are_in_this_library() = behaviour(startAt = DURING) {
        val event = given {
            extensionUploadsOnly()
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END).also { event ->
                receivedFrom(DEV_F, "FQ", final = true)
                afterTheEnd()
                step("everyone done", act = {
                    publicBackend.foreignDeviceReceived(DEV_F)
                    cycle()
                    os.foreground()
                }, until = { settled { it.of(event)?.finished == true } })
            }
        }

        // A late guest joins and shares; the guest and the fellow member both hold everything: only this member, who
        // has not received the guest's photo, keeps the event unfinished.
        whenever {
            publicBackend.foreignDevice(LATE_GUEST, listOf("GQ"), final = true)
            publicBackend.foreignDeviceReceived(LATE_GUEST)
            publicBackend.foreignDeviceReceived(DEV_F)
        }
        then { settled { it.of(event)?.finished == false } }

        // Once the guest's photo is in this library, and this member has said so, the event is finished again.
        whenever { os.foreground() }
        then {
            event<WorldEvent.DownloadStarted>()
            settled { true }
        }
        whenever {
            downloadSession.finish()
            os.foreground()
        }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported("GQ", LATE_GUEST) }
            settled { it.of(event)?.finished == true }
        }
    }

    // The share-only phone tells the event it receives nothing — its first wake, at the join — settles on Saturday
    // evening, and stays off: a photo another member adds afterwards is not owed to it, so the event is finished
    // without waiting for it.
    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "A member's share is settled only after their own range has ended",
        scenario = "A range that ends early is done early",
    )
    fun a_share_only_member_whose_range_ended_early_holds_up_nobody() = behaviour(startAt = DURING) {
        val event = given {
            extensionUploadsOnly()
            joinedEvent(
                Choices(direction = Direction.UploadOnly, until = SATURDAY_EVENING),
                startsAt = SHORT_START,
                endsAt = SHORT_END,
            ).also { event ->
                step("settled on Saturday evening", act = {
                    advanceTo(AFTER_SATURDAY_EVENING)
                    cycle()
                    heartbeat()
                }, until = {
                    event<WorldEvent.ManifestPublished> { it.event == event && it.final }
                    settled { true }
                })
                step("the phone off", act = { os.background() }, until = { settled { true } })
            }
        }

        // The others share on, and hold everything; this phone does nothing more.
        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FQ"), final = true)
            publicBackend.foreignDeviceReceived(DEV_F)
            advanceTo(AFTER_THE_END)
        }
        then {
            never<WorldEvent.ReceivedReported> { it.device != DEV_F }
            settled { it.of(event)?.finished == true }
        }
    }

    /** What the backend derives of [event] at this settle. */
    private fun WorldEvent.Settled.of(event: String): SettledEvent? = state.backendEvents?.get(event)

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

        /** A share-only member's own range end, Saturday evening inside the event, and the morning after. */
        const val SATURDAY_EVENING = "2026-05-16T20:00:00Z"
        const val AFTER_SATURDAY_EVENING = "2026-05-17T09:00:00Z"

        /** A guest who joins after the end. */
        const val LATE_GUEST = "1a7e0000-0000-4000-8000-000000000001"
    }
}
