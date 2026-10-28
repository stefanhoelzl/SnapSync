package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **What the core decides of receiving** (capability `receiving-photos`): which of the event's photos this device
 * receives, when it starts, that a photo it already received is never saved again — deleted, or shared into a second
 * event — and that a received photo stays out of what the member shares. Told by the photo library, the transfers and
 * the backend; how a platform saves a photo's bytes is its adapter's and its contracts'.
 */
class ReceivingIntegrationTest {

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Other members' photos arrive in the photo library automatically",
        scenario = "A co-member's photo appears in the library",
    )
    fun a_fellow_members_photo_arrives_in_the_background_without_the_member_doing_anything() = behaviour {
        // The app is not in front and builds no screen: everything below is the operating system's doing.
        val event = given {
            joinedEvent().also {
                pushToken("DEADBEEF")
                backgroundLaunch()
            }
        }

        whenever { publicBackend.foreignDevice(DEV_F, listOf("FQ")) }
        then { event<WorldEvent.PushSent> { it.event == event } }

        whenever { os.silentPush(event) }
        then {
            event<WorldEvent.DownloadStarted> { it.names("FQ") }
            never<WorldEvent.UiChanged>()
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FQ") }
            never<WorldEvent.UiChanged>()
            settled { imported("FQ") in it.state.libraryPhotos.orEmpty() }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Other members' photos arrive in the photo library automatically",
        scenario = "Joining a live event brings its history",
    )
    fun joining_an_event_that_already_holds_photos_starts_receiving_them() = behaviour {
        given {
            val event = createdEvent()
            step("$DEV_F shares FA and FB before this member joins", act = {
                publicBackend.foreignDevice(DEV_F, listOf("FA", "FB"), event = event)
            }, until = { settled { true } })
        }

        // The join itself starts them — no opening, no wake.
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            event<WorldEvent.DownloadStarted> { it.names("FA") }
            event<WorldEvent.DownloadStarted> { it.names("FB") }
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FA") }
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FB") }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Other members' photos arrive in the photo library automatically",
        scenario = "Own photos are not received back",
    )
    fun this_devices_own_shared_photos_are_never_downloaded_back() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("MINE")
            step("MINE shared to the event", act = { uploadAll() }, until = {
                event<WorldEvent.BytesStored> { it.asset == "MINE" }
                event<WorldEvent.ManifestPublished> { "MINE" in it.added }
                settled { true }
            })
        }

        // The event now holds MINE and a fellow member's FQ: only FQ is downloaded and saved.
        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            os.foreground()
        }
        then {
            event<WorldEvent.DownloadStarted> { it.names("FQ") }
            never<WorldEvent.DownloadStarted> { !it.names("FQ") }
            settled { true }
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FQ") }
            never<WorldEvent.LibraryAssetCreated> { it.asset != imported("FQ") }
            settled { it.state.libraryPhotos == listOf("MINE", imported("FQ")) }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos keep full fidelity",
        scenario = "A received photo sorts by capture time",
    )
    fun a_photo_taken_yesterday_is_saved_at_its_capture_time_not_today() = behaviour {
        // Every fellow member's photo was taken at `Rig.PHOTO_DATE`; it is received the day after.
        given {
            joinedEvent()
            step("the next day", act = { advanceTo(THE_NEXT_DAY) }, until = { settled { true } })
            downloadStarted(DEV_F, "FQ")
        }

        whenever { downloadSession.finish() }
        then { event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FQ") && it.date == Rig.PHOTO_DATE } }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "A deleted received photo never comes back",
        scenario = "Deleting a received photo",
    )
    fun a_received_photo_the_member_deleted_is_not_received_again_when_new_photos_arrive() = behaviour {
        given {
            joinedEvent()
            receivedFrom(DEV_F, "FA")
            step("FA deleted from the library", act = { library.remove(imported("FA")) }, until = {
                settled { it.state.libraryPhotos.orEmpty().isEmpty() }
            })
        }

        // The fellow member shares FB beside FA: only FB is downloaded and saved.
        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FA", "FB"))
            os.foreground()
        }
        then {
            event<WorldEvent.DownloadStarted> { it.names("FB") }
            never<WorldEvent.DownloadStarted> { it.names("FA") }
            settled { true }
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FB") }
            never<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FA")) }
            settled { it.state.libraryPhotos == listOf(imported("FB")) }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "A deleted received photo never comes back",
        scenario = "The same photo in two events",
    )
    fun a_photo_received_in_one_event_is_not_saved_again_by_another_that_holds_it() = behaviour {
        given {
            joinedEvent()
            receivedFrom(DEV_F, "FQ")
            step("left", act = { tap(UiIntent.LeaveEvent) }, until = {
                screen<Layer.CreateEvent>()
                settled { true }
            })
            val second = createdEvent(name = "Second Event")
            step("$DEV_F shares FQ into it too", act = {
                publicBackend.foreignDevice(DEV_F, listOf("FQ"), event = second)
            }, until = { settled { true } })
        }

        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            never<WorldEvent.DownloadStarted>()
            never<WorldEvent.LibraryAssetCreated>()
            settled { it.state.libraryPhotos == listOf(imported("FQ")) }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A received photo stays out of the member's contribution",
    )
    fun a_received_photo_inside_the_range_is_neither_uploaded_nor_counted_nor_announced() = behaviour {
        // FQ carries `Rig.PHOTO_DATE`, inside the member's range, as MINE does.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("MINE")
            receivedFrom(DEV_F, "FQ")
        }

        whenever {
            uploadAll()
            advance(STATUS_POLL)
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "MINE" }
            never<WorldEvent.BytesStored> { it.asset != "MINE" }
            never<WorldEvent.UploadJobCreated> { !it.job.startsWith("MINE/") }
            // Nothing announces it to the others as this member's, so no one receives it a second time.
            never<WorldEvent.ManifestPublished> { it.device != DEV_F && it.added.any { asset -> asset != "MINE" } }
            screen<Layer.Joined> { it.sharedTotal() == 1 }
            neverScreen<Layer.Joined> { (it.sharedTotal() ?: 0) > 1 }
        }
    }

    /** Whether this download is of the fellow member's photo [asset]. */
    private fun WorldEvent.DownloadStarted.names(asset: String) = description.split("\n").getOrNull(1) == asset

    /** What the joined screen counts as the member's to share. */
    private fun Layer.Joined.sharedTotal(): Int? = (counts?.shared as? DirectionCount.Progress)?.total

    private companion object {
        /** The day after `Rig.PHOTO_DATE`. */
        const val THE_NEXT_DAY = "2026-06-02T12:00:00Z"

        /** Long enough for the joined screen's status poll (every 2 s) to read again. */
        val STATUS_POLL = 5.seconds
    }
}
