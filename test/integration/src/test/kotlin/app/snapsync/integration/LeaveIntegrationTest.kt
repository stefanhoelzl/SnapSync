package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.NetworkState
import app.snapsync.levers.WorldEvent
import app.snapsync.levers.WriteKind
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **Where a membership ends, and where it does not** (capability `manage-membership`), over the real composition: a
 * leave is instant and works offline, stops what is under way and is never half-done; Stay changes nothing; an update
 * keeps the event; a reinstall starts unjoined.
 *
 * Whether the app shares or receives is read off what it hands the operating system — upload jobs, session transfers,
 * downloads — and what the event offers its members (`UnionChanged`); what the photo library gained, off its imports.
 */
class LeaveIntegrationTest {

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Leaving asks first, then is instant and works offline",
        scenario = "Leaving while offline",
    )
    fun leaving_offline_shows_the_create_screen_at_once_and_moves_nothing_more() = behaviour {
        given {
            joinedEvent()
            step("a fellow member's photo in the event", act = {
                publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            }, until = { settled { true } })
            step("offline", act = { network(NetworkState.OFFLINE) }, until = {})
        }

        whenever { tap(UiIntent.LeaveEvent) }
        then { screen<Layer.CreateEvent>() }

        // Not later either, the network back and the app opened: nothing is shared or received for the event left.
        whenever {
            takePhoto("A")
            network(NetworkState.ONLINE)
            os.foreground()
            cycle()
        }
        then {
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.DownloadStarted>()
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Leaving asks first, then is instant and works offline",
        scenario = "Choosing Stay changes nothing",
    )
    fun choosing_stay_keeps_the_member_sharing_and_receiving() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            step("asked whether to leave", act = { tap(UiIntent.ConfirmLeaveOpen) }, until = {
                event<WorldEvent.UiChanged> { it.ui.overlays.confirmingLeave }
            })
        }

        whenever { tap(UiIntent.ConfirmLeaveDismiss) }
        then {
            event<WorldEvent.UiChanged> { !it.ui.overlays.confirmingLeave && it.ui.layer is Layer.Joined }
            never<WorldEvent.BackendReceived> { it.operation == "leaveEvent" }
            neverScreen<Layer.CreateEvent>()
        }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            takePhoto("A")
            os.foreground()
            cycle()
        }
        then {
            event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") }
            event<WorldEvent.DownloadStarted>()
        }
    }

    @Test
    @Verifies(spec = "manage-membership", requirement = "Leaving asks first, then is instant and works offline")
    fun a_leave_the_phone_cannot_write_keeps_the_member_fully_joined_and_tells_the_service_nothing() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            step(
                "the membership's next delete refused",
                act = { databases.fail("ledger.db", "membership", WriteKind.DELETE) },
                until = {},
            )
        }

        whenever { tap(UiIntent.LeaveEvent) }
        then {
            event<WorldEvent.DatabaseFaulted> { it.table == "membership" && it.kind == WriteKind.DELETE }
            never<WorldEvent.BackendReceived> { it.operation == "leaveEvent" }
            neverScreen<Layer.CreateEvent>()
        }

        // Still sharing and receiving, as before the leave was tried.
        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            takePhoto("A")
            os.foreground()
            cycle()
        }
        then {
            event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") }
            event<WorldEvent.DownloadStarted>()
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Leaving asks first, then is instant and works offline",
        scenario = "Leave cancels transfers under way",
    )
    fun a_leave_stops_the_upload_and_the_download_under_way() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            step("photo A taken", act = { takePhoto("A") }, until = { settled { true } })
            step("A uploading", act = { cycle() }, until = {
                event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") }
                settled { true }
            })
            step("a fellow member's photo downloading", act = {
                publicBackend.foreignDevice(DEV_F, listOf("FQ"))
                os.foreground()
            }, until = {
                event<WorldEvent.DownloadStarted>()
                settled { true }
            })
        }

        whenever { tap(UiIntent.LeaveEvent) }
        then { screen<Layer.CreateEvent>() }

        // The operating system would have finished both now: neither lands.
        whenever {
            uploadQueue.completeJob(null)
            downloadSession.finish()
        }
        then {
            never<WorldEvent.UnionChanged> { it.asset == "A" }
            never<WorldEvent.LibraryAssetCreated>()
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Leaving asks first, then is instant and works offline",
        scenario = "Leaving a deleted event does not ask",
    )
    fun leaving_a_deleted_event_shows_the_create_screen_at_once() = behaviour {
        // That its Leave asks nothing is the screen's (`StatusScreenTest`): the joined layer of a deleted event hands the
        // leave itself over. Here: that leave, from a deleted event, ends the membership at once.
        given {
            val event = joinedEvent()
            received(DEV_F, "FQ")
            deletedOnBackend(event)
        }

        whenever { tap(UiIntent.LeaveEvent) }
        then { screen<Layer.CreateEvent>() }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A membership survives updates, restarts, and a locked phone",
        scenario = "An app update keeps the event",
    )
    fun an_update_keeps_the_event_and_every_setting() = behaviour {
        lateinit var joined: Seen<Layer.Joined>
        given {
            createdEvent()
            step("joined, receiving only, no album", act = {
                confirmJoin(Choices(direction = Direction.DownloadOnly, saveToAlbum = false))
            }, until = { joined = screen<Layer.Joined>() })
        }
        val before = joined.value.membership

        whenever {
            build.declare(NEWER_VERSION)
            host.relaunch()
        }
        then {
            screen<Layer.Joined> { it.membership == before }
            neverScreen<Layer.CreateEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Deleting the app counts as leaving",
        scenario = "A reinstall starts unjoined",
    )
    fun a_reinstall_opens_on_the_create_screen_and_the_invite_offers_to_join_again() = behaviour {
        lateinit var event: String
        val link = given {
            event = joinedEvent()
            inviteFor(event)
        }

        whenever { host.reinstall() }
        then {
            screen<Layer.CreateEvent>()
            neverScreen<Layer.Joined>()
        }

        whenever { openLink(link) }
        then {
            screen<Layer.JoiningEvent> {
                it.eventId == event && (it.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready
            }
        }
    }

    private companion object {
        /** A marketing version newer than any this build declares: the update. */
        const val NEWER_VERSION = "99.0"
    }
}
