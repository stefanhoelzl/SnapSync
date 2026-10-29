package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.NetworkState
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **Photos travel on their own** (capability `delivery`), over the real composed stack: uploads that outlive the app in
 * front, the network or a revoked grant; downloads a silent wake or the next opening brings, and that a leave, a
 * switch or a force-quit stops. The operator plays the operating system's sessions, its wakes and its network.
 */
class DeliveryIntegrationTest {

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "Uploads continue after the app is suspended",
    )
    fun uploads_continue_after_the_app_is_left_and_queued_photos_follow() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            sessionFull()
        }

        whenever {
            os.background()
            uploadSession.complete()
        }
        then {
            times(SESSION_CAP) { event<WorldEvent.BytesStored>() }
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("P$SESSION_CAP") }
            never<WorldEvent.UiChanged>()
        }

        whenever { uploadSession.complete() }
        then { event<WorldEvent.BytesStored> { it.asset == "P$SESSION_CAP" } }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "Uploads wait for Wi-Fi when the member chose so",
    )
    fun a_photo_kept_off_mobile_data_uploads_in_the_background_once_on_wifi() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            step("mobile data off", act = { tap(UiIntent.MobileData(false)) }, until = { settled { true } })
            step("in the pocket, on mobile data", act = {
                os.background()
                network.set(NetworkState.RESTRICTED)
            }, until = { settled { true } })
            step("P1 taken, a wake hands it over", act = {
                takePhoto("P1")
                heartbeat()
            }, until = {
                event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("P1") }
                settled { true }
            })
        }

        whenever { uploadSession.complete() }
        then {
            never<WorldEvent.BytesStored>()
            settled { it.state.inFlight?.sessionTransfers == listOf(primaryKey("P1")) }
        }

        whenever {
            network.set(NetworkState.ONLINE)
            uploadSession.complete()
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "P1" }
            never<WorldEvent.UiChanged>()
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A photo is never lost on the way",
        scenario = "Offline photos upload when the network returns",
    )
    fun a_photo_taken_offline_uploads_once_a_connection_is_back() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            step("in the pocket, offline", act = {
                os.background()
                network(NetworkState.OFFLINE)
            }, until = { settled { true } })
            step("P1 taken offline, a wake runs", act = {
                takePhoto("P1")
                heartbeat()
            }, until = { settled { true } })
        }

        whenever {
            network(NetworkState.ONLINE)
            heartbeat()
            uploadSession.complete()
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "P1" }
            never<WorldEvent.UiChanged>()
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Download problems delay photos, never corrupt or lose them",
        scenario = "Offline, but already downloaded",
    )
    fun a_finished_download_is_saved_while_the_event_cannot_be_reached() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
        }

        whenever {
            backend.offline(true)
            downloadSession.finish()
        }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FQ")) }
            settled { it.state.libraryPhotos.orEmpty().any { photo -> photo.startsWith(imported("FQ")) } }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Losing photo access stops new uploads but lets in-flight ones finish",
        scenario = "Revoking access mid-upload",
    )
    fun revoking_access_lets_the_uploads_under_way_finish_and_starts_no_other() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            sessionFull()
        }

        whenever {
            library.permission(PhotoGrant.DENIED)
            uploadSession.complete()
        }
        then {
            times(SESSION_CAP) { event<WorldEvent.BytesStored>() }
            never<WorldEvent.SessionTransferCreated>()
            settled { it.state.inFlight?.sessionTransfers.orEmpty().isEmpty() }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Losing photo access stops new uploads but lets in-flight ones finish",
        scenario = "No background permission prompt",
    )
    fun a_background_wake_before_access_was_ever_answered_asks_nothing_and_uploads_nothing() = behaviour {
        val event = given {
            joinedEvent().also {
                photoAccess(PhotoGrant.NOT_DETERMINED)
                photos("A")
                backgroundLaunch()
            }
        }

        whenever {
            heartbeat()
            os.silentPush(event)
            cycle()
        }
        then {
            never<WorldEvent.LibraryAccessAsked>()
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.UiChanged>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Leaving or switching stops every transfer for the old event",
        scenario = "Leaving stops downloads",
    )
    fun leaving_saves_no_further_photo_and_keeps_those_saved() = behaviour {
        given {
            joinedEvent()
            downloadStarted(asset = "FQ")
            step("FQ saved", act = { downloadSession.finish() }, until = {
                event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FQ")) }
                settled { true }
            })
            downloadStarted(asset = "FR")
        }

        whenever {
            tap(UiIntent.LeaveEvent)
            downloadSession.finish()
        }
        then {
            never<WorldEvent.LibraryAssetCreated>()
            settled { it.state.libraryPhotos.orEmpty().any { photo -> photo.startsWith(imported("FQ")) } }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Leaving or switching stops every transfer for the old event",
        scenario = "A switch redirects uploads",
    )
    fun a_switch_sends_nothing_more_to_the_old_event_and_uploads_to_the_new_one() = behaviour {
        val (old, new) = given {
            appUploadsOnly()
            val new = registeredEvent(name = "Anna's Wedding")
            val old = joinedEvent()
            step("A on its way to the old event", act = {
                takePhoto("A")
                os.foreground()
            }, until = {
                event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
                settled { true }
            })
            switchedTo(new)
            old to new
        }

        // The old event's upload, finished by the operating system after the switch, never reaches it.
        whenever { uploadSession.complete() }
        then {
            never<WorldEvent.BytesStored> { it.event == old }
            settled { true }
        }

        whenever {
            takePhoto("B")
            os.foreground()
        }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") } }

        whenever { uploadSession.complete() }
        then {
            event<WorldEvent.BytesStored> { it.event == new && it.asset == "B" }
            never<WorldEvent.BytesStored> { it.event == old }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Leaving or switching stops every transfer for the old event",
        scenario = "Rejoining receives again",
    )
    fun a_member_who_left_and_joins_again_receives_the_others_photos() = behaviour {
        val next = given {
            val next = registeredEvent(name = "Anna's Wedding")
            joinedEvent()
            left()
            step("a fellow member shares to the next event", act = {
                publicBackend.foreignDevice(DEV_F, listOf("FQ"), event = next)
            }, until = { settled { true } })
            next
        }

        whenever { openLink(inviteLink(next)) }
        then { screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.step == READY } }

        whenever {
            confirmJoin()
            downloadSession.finish()
        }
        then {
            event<WorldEvent.DownloadStarted>()
            times(1) { event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FQ")) } }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Only new photos are announced by a silent wake, and never only by it",
        scenario = "A wake brings new photos in the background",
    )
    @Verifies(
        spec = "app-experience",
        requirement = "Background wakes do their work without showing anything",
        scenario = "A silent notification wakes the app",
    )
    fun a_silent_wake_brings_a_new_photo_into_the_library_without_the_app_opened() = behaviour {
        val event = given {
            joinedEvent().also {
                pushToken(TOKEN)
                backgroundLaunch()
            }
        }

        whenever { publicBackend.foreignDevice(DEV_F, listOf("FQ")) }
        then { event<WorldEvent.PushSent> { it.event == event && it.token == TOKEN } }

        whenever { os.silentPush(event) }
        then { event<WorldEvent.DownloadStarted>() }

        whenever { downloadSession.finish() }
        then {
            times(1) { event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FQ")) } }
            never<WorldEvent.UiChanged>()
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Only new photos are announced by a silent wake, and never only by it",
        scenario = "Opening the app catches up",
    )
    fun opening_the_app_brings_a_photo_no_wake_announced() = behaviour {
        given {
            joinedEvent()
            backgroundLaunch()
        }

        whenever { publicBackend.foreignDevice(DEV_F, listOf("FQ")) }
        then {
            never<WorldEvent.DownloadStarted>()
            settled { true }
        }

        whenever {
            os.foreground()
            downloadSession.finish()
        }
        then {
            event<WorldEvent.DownloadStarted>()
            times(1) { event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FQ")) } }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Only new photos are announced by a silent wake, and never only by it",
        scenario = "A force-quit stops receiving until the next opening",
    )
    fun after_a_force_quit_nothing_arrives_until_the_app_is_opened_and_then_everything_does() = behaviour {
        given {
            joinedEvent()
            downloadStarted(asset = "FQ")
            step("in the background", act = { os.background() }, until = { settled { true } })
        }

        whenever {
            playedOs.forceQuit()
            publicBackend.foreignDevice(DEV_F, listOf("FQ", "FR"))
            downloadSession.finish()
        }
        then {
            event<WorldEvent.ForceQuit>()
            never<WorldEvent.LibraryAssetCreated>()
            settled { true }
        }

        whenever {
            os.foreground()
            downloadSession.finish()
        }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FQ")) }
            event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FR")) }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Only new photos are announced by a silent wake, and never only by it",
        scenario = "A left event's wakes are ignored",
    )
    fun a_wake_for_a_left_event_downloads_nothing() = behaviour {
        val event = given {
            joinedEvent().also {
                pushToken(TOKEN)
                left()
            }
        }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FQ"), event = event)
            os.silentPush(event)
        }
        then {
            never<WorldEvent.BackendReceived> { it.operation == EVENT_FILES }
            never<WorldEvent.DownloadStarted>()
            settled { true }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private suspend fun Given.appUploadsOnly() =
        step("only the app uploads", act = { devControls.pin(app = true, extension = false) }, until = {})

    /** More photos than the app's upload session holds at once, the app in front: the session full, two waiting. */
    private suspend fun Given.sessionFull() = step(
        "the session full, two photos waiting",
        act = {
            repeat(SESSION_CAP + 2) { takePhoto("P$it") }
            os.foreground()
        },
        until = {
            times(SESSION_CAP) { event<WorldEvent.SessionTransferCreated>() }
            settled { true }
        },
    )

    /** The invite of [next] opened, the switch confirmed, and [next] joined. */
    private suspend fun Given.switchedTo(next: String) {
        step(
            "the other event's invite opened, and the switch asked",
            act = { openLink(inviteLink(next)) },
            until = { screen<Layer.Joined> { (it.pendingSwitch?.phase as? JoinPhase.Detailed)?.step == READY } },
        )
        step(
            "the switch confirmed",
            act = { tap(UiIntent.ConfirmSwitch) },
            until = { screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.step == READY } },
        )
        step("switched to the other event", act = { confirmJoin() }, until = {
            screen<Layer.Joined> { it.membership.eventId == next }
            settled { true }
        })
    }

    private companion object {
        /** The live transfers the app uploader's session holds at once. */
        const val SESSION_CAP = 4
        const val TOKEN = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
        const val EVENT_FILES = "eventFiles"
        val READY = JoinPhase.Detailed.Step.Ready
    }
}
