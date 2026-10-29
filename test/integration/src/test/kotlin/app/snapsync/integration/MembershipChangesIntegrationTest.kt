package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.NetworkState
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RenameState
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **Changing a membership in place** (capability `manage-membership`), over the real composition: the rename — offline,
 * cancelled, after the end, after the deletion — and settings that take effect at once — receiving turned on, the range
 * narrowed and widened back.
 *
 * What the event offers its members is read off the backend's union (`UnionChanged`); the name every other member reads
 * is the one the service serves, which the join screen of anyone opening the invite shows.
 */
class MembershipChangesIntegrationTest {

    // ── rename ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A failed rename keeps the dialog and never ends the membership",
        scenario = "Offline rename",
    )
    fun an_offline_rename_keeps_the_dialog_open_and_says_it_failed() = behaviour {
        given {
            joinedEvent(name = WEEKEND)
            renameDialogOpen()
            step("offline", act = { network(NetworkState.OFFLINE) }, until = {})
        }

        whenever { rename(ANAS_30TH) }
        then {
            event<WorldEvent.UiChanged> { changed ->
                val joined = changed.ui.layer as? Layer.Joined
                changed.ui.overlays.renaming && joined != null &&
                    joined.renameState is RenameState.Failed &&
                    joined.membership.name == WEEKEND
            }
            neverScreen<Layer.CreateEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Any member renames the event for everyone until its photos are deleted",
        scenario = "Cancelling a rename changes nothing",
    )
    fun cancelling_the_rename_dialog_keeps_the_name() = behaviour {
        given {
            joinedEvent(name = WEEKEND)
            renameDialogOpen()
        }

        whenever { tap(UiIntent.RenameDismiss) }
        then {
            event<WorldEvent.UiChanged> { changed ->
                !changed.ui.overlays.renaming && (changed.ui.layer as? Layer.Joined)?.membership?.name == WEEKEND
            }
            never<WorldEvent.BackendReceived> { it.operation == CHANGE_EVENT }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Any member renames the event for everyone until its photos are deleted",
        scenario = "An ended event can still be renamed",
    )
    @Verifies(
        spec = "event-lifetime",
        requirement = "The date range closes nothing",
        scenario = "A rename after the end",
    )
    fun an_event_a_week_past_its_end_is_renamed_for_everyone() = behaviour {
        lateinit var event: String
        val link = given {
            event = joinedEvent(name = WEEKEND, startsAt = SHORT_START, endsAt = SHORT_END)
            val link = inviteFor(event)
            laterInThePocket(A_WEEK_AFTER_THE_END)
            inFront()
            link
        }

        whenever { rename(ANAS_30TH) }
        then {
            screen<Layer.Joined> { it.renameState == RenameState.Succeeded && it.membership.name == ANAS_30TH }
        }

        // What any other member reads: opening the invite, the service names the event by its new name.
        whenever { tap(UiIntent.LeaveEvent) }
        then { screen<Layer.CreateEvent>() }
        whenever { openLink(link) }
        then { screen<Layer.JoiningEvent> { it.eventId == event && (it.phase as? JoinPhase.Detailed)?.eventName == ANAS_30TH } }
    }

    // That a deleted event's joined screen offers no rename at all is the screen's (`StatusScreenTest`); this is the
    // requirement's other half: a rename that reaches the service after the deletion fails like any refused one.
    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Any member renames the event for everyone until its photos are deleted",
    )
    fun a_rename_after_the_deletion_fails_and_keeps_the_member_in_the_event() = behaviour {
        given {
            val event = joinedEvent(name = WEEKEND)
            deletedOnBackend(event)
        }

        whenever { rename(ANAS_30TH) }
        then {
            screen<Layer.Joined> { it.deleted && it.renameState is RenameState.Failed && it.membership.name == WEEKEND }
            neverScreen<Layer.CreateEvent>()
        }
    }

    // ── settings ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A settings change takes effect as soon as it applies",
        scenario = "Turning receiving on starts right away",
    )
    fun turning_receiving_on_starts_bringing_in_the_events_photos() = behaviour {
        given {
            joinedEvent(Choices(direction = Direction.UploadOnly))
            step("a fellow member's photo in the event, not received", act = {
                publicBackend.foreignDevice(DEV_F, listOf("FQ"))
                os.foreground()
            }, until = {
                never<WorldEvent.DownloadStarted>()
                settled { true }
            })
        }

        whenever { reconfigure(Choices(direction = Direction.Both)) }
        then {
            screen<Layer.Joined> { it.membership.receives }
            event<WorldEvent.DownloadStarted>()
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A settings change takes effect as soon as it applies",
        scenario = "Narrowing then widening restores the photos",
    )
    fun a_photo_withdrawn_by_a_narrower_range_is_offered_again_once_it_is_widened() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            shared("A")
            step("the range narrowed past it", act = {
                reconfigure(Choices(cutoff = AFTER_THE_PHOTO))
                cycle()
            }, until = { event<WorldEvent.UnionChanged> { it.asset == "A" && !it.gained } })
        }

        whenever {
            reconfigure(Choices(cutoff = BEFORE_THE_PHOTO))
            cycle()
        }
        then { event<WorldEvent.UnionChanged> { it.asset == "A" && it.gained } }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The rename dialog open over the joined screen. */
    private suspend fun Given.renameDialogOpen() = step(
        "the rename dialog open",
        act = { tap(UiIntent.RenameOpen) },
        until = { event<WorldEvent.UiChanged> { it.ui.overlays.renaming } },
    )

    private companion object {
        const val WEEKEND = "Weekend"
        const val ANAS_30TH = "Ana's 30th"
        const val CHANGE_EVENT = "changeEvent"

        /** A short range around the start of every behaviour's clock (1 June noon). */
        const val SHORT_START = "2026-05-25T00:00:00"
        const val SHORT_END = "2026-06-02T00:00:00"

        /** A week past [SHORT_END]; long before its photos may be deleted. */
        const val A_WEEK_AFTER_THE_END = "2026-06-09T12:00:00Z"

        /** Starts of the member's range on either side of [Defaults.PHOTO_DATE], inside the default event window. */
        const val AFTER_THE_PHOTO = "2026-06-03T00:00:00Z"
        const val BEFORE_THE_PHOTO = "2026-05-20T00:00:00Z"
    }
}
