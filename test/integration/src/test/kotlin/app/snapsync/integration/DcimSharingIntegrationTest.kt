package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.LibraryPhotoKind
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **What of an Android member's DCIM folder is shared** (`openspec/specs/photo-sharing`, "On Android under full access,
 * only photos in the DCIM folder are shared", and under a partial grant "Under limited access the member's selection
 * decides what a photo is"), the scenarios the app's own selection policy decides, one per test.
 *
 * Which photos are in DCIM at all is the Android gallery adapter's answer, proven against MediaStore by its contract on
 * an emulator; what the app is handed is that default gallery, each subfolder an album. So the library here is an
 * Android phone's ([androidLibrary]), a photo in a subfolder is one [LibraryLevers.placeIn] files there — placed before
 * it is taken, as the phone saves it there — and Android marks no photo as a screenshot or a screen recording: its
 * recordings are plain videos ([LibraryPhotoKind.HD_VIDEO]), known only by their folder. A photo is *shared* when it
 * leaves the device: the app's own upload session takes it ([primaryKey]) — here as the app comes to the front, since
 * the OS waking it for a new photo is a phone's, not the app's.
 */
class DcimSharingIntegrationTest {

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "On Android under full access, only photos in the DCIM folder are shared",
        scenario = "A third-party camera app that saves into DCIM is shared",
    )
    fun a_photo_in_a_camera_app_s_own_dcim_folder_leaves_the_device() = behaviour {
        given {
            androidLibrary()
            joinedEvent()
        }

        whenever {
            library.placeIn("OpenCamera", "A")
            takePhoto("A")
            os.foreground()
        }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") } }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "On Android under full access, only photos in the DCIM folder are shared",
        scenario = "A screen recording in DCIM is not shared",
    )
    fun a_recording_in_the_screen_recordings_folder_stays_while_a_camera_video_leaves() = behaviour {
        given {
            androidLibrary()
            joinedEvent()
        }

        whenever {
            library.placeIn("Screen recordings", "REC")
            library.add("REC", kind = LibraryPhotoKind.HD_VIDEO, date = Rig.PHOTO_DATE)
            library.add("CLIP", kind = LibraryPhotoKind.HD_VIDEO, date = Rig.PHOTO_DATE)
            os.foreground()
        }
        then {
            // The same video, but filmed with the camera: only the folder told the recording apart.
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("CLIP") }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("REC") }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "On Android under full access, only photos in the DCIM folder are shared",
        scenario = "A small image in DCIM is excluded",
    )
    fun an_image_below_the_floor_in_the_camera_folder_stays_while_a_photo_leaves() = behaviour {
        given {
            androidLibrary()
            joinedEvent()
        }

        whenever {
            library.add("SMALL", kind = LibraryPhotoKind.LOW_RES, date = Rig.PHOTO_DATE)
            takePhoto("A")
            os.foreground()
        }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("SMALL") }
        }
    }

    // ---- under limited access the member's selection decides what a photo is -------------------------------------

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Under limited access the member's selection decides what a photo is",
        scenario = "A selected photo from a WhatsApp folder is shared on Android",
    )
    fun a_selected_photo_in_a_whatsapp_folder_leaves_the_device() = behaviour {
        // Android's folders stay readable under a partial grant — but what the member selected is theirs to share.
        given {
            androidLibrary()
            limitedMember()
            step("a photo saved into a WhatsApp folder", act = {
                library.placeIn("WhatsApp", "WA")
                takePhoto("WA")
            }, until = { settled { true } })
        }

        whenever { library.changeSelection(listOf("WA")) }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("WA") } }
    }
}
