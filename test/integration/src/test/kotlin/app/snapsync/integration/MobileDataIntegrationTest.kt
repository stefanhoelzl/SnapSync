package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.NetworkState
import app.snapsync.levers.TransferEnd
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Arrow
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **Photos kept off mobile data wait for Wi-Fi**, over the real composed stack, driven through the control protocol.
 *
 * The operator plays the device's network (`network.set(RESTRICTED)` — mobile data, a hotspot, Low Data Mode) and the
 * operating system's transfers: it lands what the platform would run, and the platform runs no transfer held to an
 * unrestricted network while the network is restricted. The app's own uploader is the one under test — the OS-driven
 * queue holds every job on a restricted network whatever the member chose (measured; `UploadQueueMock`), so it is
 * switched off here.
 *
 * The choice is the device's, flipped from the app menu (decision record `changes/archive/2026-10-07-mobile-data-per-device`).
 */
class MobileDataIntegrationTest {

    private suspend fun Given.appUploadsOnly() =
        step("only the app uploads", act = { devControls.pin(app = true, extension = false) }, until = {})

    /** The app menu's switch: the device's choice, applied as it is flipped. */
    private suspend fun Given.mobileData(on: Boolean) =
        step("mobile data ${if (on) "on" else "off"}", act = { tap(UiIntent.MobileData(on)) }, until = {
            event<WorldEvent.UiChanged> { it.ui.mobileData.on == on }
            settled { true }
        })

    /** The device's network is [state], as the operating system reports it. */
    private suspend fun Given.network(state: NetworkState) =
        step("the network $state", act = { network.set(state) }, until = { settled { true } })

    /** The app's uploader picks up [photo]: the foreground's tail creates its transfer. */
    private suspend fun Given.sharedFromForeground(photo: String) =
        step("$photo taken, its transfer created", act = { shareFromForeground(photo) }, until = {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey(photo) }
            settled { true }
        })

    private suspend fun Acts.shareFromForeground(photo: String) {
        takePhoto(photo)
        os.foreground()
    }

    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "The member chooses for the device whether photos may use mobile data",
        scenario = "Untouched, photos use any network",
    )
    fun an_untouched_choice_shares_over_mobile_data() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            network(NetworkState.RESTRICTED)
            sharedFromForeground("P1")
        }
        whenever { uploadSession.complete() }
        then { event<WorldEvent.BytesStored> { it.asset == "P1" } }
    }

    @Test
    @Verifies(spec = "mobile-data", requirement = "With mobile data off, photos wait for an unrestricted Wi-Fi")
    @Verifies(
        spec = "sync-status",
        requirement = "Direction arrows show the work left and the transfer running",
        scenario = "Photos waiting for Wi-Fi are named",
    )
    fun with_mobile_data_off_an_upload_waits_for_wifi_and_says_so() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            mobileData(false)
            network(NetworkState.RESTRICTED)
            step("P1 taken, its transfer created", act = {
                shareFromForeground("P1")
                advance(5.seconds) // the screen's status poll
            }, until = {
                event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("P1") }
                screen<Layer.Joined> { it.health == WAITING_FOR_WIFI } // still arrows, and the line says it waits
                settled { true }
            })
        }

        whenever {
            uploadSession.complete()
            advance(5.seconds)
        }
        then {
            // the platform runs no held transfer on a restricted network
            never<WorldEvent.BytesStored>()
            never<WorldEvent.SessionTransferEnded>()
            settled { it.state.inFlight?.sessionTransfers == listOf(primaryKey("P1")) }
            // and the line still says the photo waits for Wi-Fi
            neverScreen<Layer.Joined> { it.health != WAITING_FOR_WIFI }
        }

        whenever {
            network.set(NetworkState.ONLINE)
            uploadSession.complete()
            advance(5.seconds)
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "P1" }
            screen<Layer.Joined> { (it.health as? SyncHealth.Syncing)?.waitingForWifi != true }
        }
    }

    @Test
    @Verifies(spec = "mobile-data", requirement = "With mobile data off, photos wait for an unrestricted Wi-Fi")
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "Downloads wait for Wi-Fi when the member chose so",
    )
    fun with_mobile_data_off_a_received_photo_waits_for_wifi() = behaviour {
        given {
            joinedEvent(Choices(direction = Direction.DownloadOnly))
            mobileData(false)
            network(NetworkState.RESTRICTED)
            step("a fellow member's photo on its way", act = {
                publicBackend.foreignDevice(DEV_F, listOf("FA"))
                os.foreground()
            }, until = {
                event<WorldEvent.DownloadStarted>()
                settled { it.state.inFlight?.downloads?.size == 1 }
            })
        }

        whenever { downloadSession.finish() }
        then {
            // the platform runs no held download on a restricted network
            never<WorldEvent.DownloadEnded>()
            never<WorldEvent.LibraryAssetCreated>()
            settled { it.state.inFlight?.downloads?.size == 1 }
        }

        whenever {
            network.set(NetworkState.ONLINE)
            downloadSession.finish()
        }
        then {
            event<WorldEvent.DownloadEnded> { it.outcome.how == TransferEnd.How.DELIVERED }
            times(1) { event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FA")) } }
        }
    }

    @Test
    @Verifies(spec = "mobile-data", requirement = "A change applies to transfers that start afterwards")
    fun a_change_governs_only_the_transfers_that_start_after_it() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            mobileData(false)
            network(NetworkState.RESTRICTED)
            sharedFromForeground("P1")
            mobileData(true)
            sharedFromForeground("P2")
        }

        whenever { uploadSession.complete() }
        then {
            event<WorldEvent.BytesStored> { it.asset == "P2" }
            // the transfer started under the old rule keeps it and still waits
            never<WorldEvent.BytesStored> { it.asset == "P1" }
        }
    }

    @Test
    @Verifies(spec = "mobile-data", requirement = "Everything but photo transfers keeps working on any network")
    fun joining_and_renaming_work_on_mobile_data_with_photos_kept_off_it() = behaviour {
        val event = given {
            network(NetworkState.RESTRICTED)
            mobileData(false)
            joinedEvent()
        }
        whenever { rename("Off the plan") }
        then {
            event<WorldEvent.EventRenamed> { it.event == event && it.name == "Off the plan" }
            screen<Layer.Joined> { it.membership.eventId == event && it.membership.name == "Off the plan" }
            never<WorldEvent.UiChanged> { it.ui.mobileData.on }
        }
    }

    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "The member chooses for the device whether photos may use mobile data",
        scenario = "The choice carries to the next event",
    )
    fun the_choice_carries_to_the_next_event() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            mobileData(false)
            step("left", act = { tap(UiIntent.LeaveEvent) }, until = { screen<Layer.CreateEvent>() })
        }

        whenever {
            createEvent(Defaults.EVENT_NAME, Defaults.WINDOW_START, Defaults.WINDOW_END)
            awaitScreen("the join gate") { (it.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed }
            confirmJoin()
        }
        then {
            screen<Layer.Joined>()
            // the next event starts with the device's choice
            never<WorldEvent.UiChanged> { it.ui.mobileData.on }
            settled { true }
        }

        whenever {
            network.set(NetworkState.RESTRICTED)
            shareFromForeground("P1")
        }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("P1") } }

        whenever { uploadSession.complete() }
        then {
            // the new event's photo waits for Wi-Fi without choosing again
            never<WorldEvent.BytesStored>()
        }
    }

    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "The member chooses for the device whether photos may use mobile data",
        scenario = "Chosen before joining",
    )
    fun chosen_before_joining() = behaviour {
        given {
            appUploadsOnly()
            mobileData(false)
            network(NetworkState.RESTRICTED)
            joinedEvent()
            sharedFromForeground("P1")
        }

        whenever { uploadSession.complete() }
        then { never<WorldEvent.BytesStored>() } // no photo of the event travels over mobile data

        whenever {
            network.set(NetworkState.ONLINE)
            uploadSession.complete()
        }
        then { event<WorldEvent.BytesStored> { it.asset == "P1" } }
    }

    private companion object {
        val WAITING_FOR_WIFI = SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN, waitingForWifi = true)
    }
}
