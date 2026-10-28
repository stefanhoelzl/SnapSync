package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.LibraryPhotoKind
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **Under limited access the member's selection decides what a photo is** (`openspec/specs/photo-sharing/spec.md`, and
 * photo-access's switch between full and limited access; decision record `changes/archive/2026-10-15-limited-selection-decides`), one
 * scenario per test, over the real composed stack.
 *
 * A photo is *shared* here when it **leaves the device** — the session transfer naming its upload key ([primaryKey]) —
 * as in `PhotoAccessIntegrationTest`. What the other members are offered has no event of its own yet, so a photo
 * *withdrawn* is told by what the member is shown is shared (the status's own count), which is the same set
 * (photo-sharing, "What the member is told is shared is exactly what is shared"); the cycle's suite pins the manifest.
 */
class LimitedSelectionIntegrationTest {

    // ---- the selection decides what a photo is ---------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Under limited access the member's selection decides what a photo is",
        scenario = "A selected screenshot is shared",
    )
    fun a_selected_screenshot_leaves_the_device() = behaviour {
        given {
            limitedMember()
            taken("SHOT", LibraryPhotoKind.SCREENSHOT)
        }

        whenever { library.changeSelection(listOf("SHOT")) }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("SHOT") } }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Under limited access the member's selection decides what a photo is",
        scenario = "A selected screen recording is shared",
    )
    fun a_selected_screen_recording_leaves_the_device() = behaviour {
        given {
            limitedMember()
            taken("REC", LibraryPhotoKind.SCREEN_RECORDING)
        }

        whenever { library.changeSelection(listOf("REC")) }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("REC") } }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Under limited access the member's selection decides what a photo is",
        scenario = "A selected WhatsApp photo is shared on iPhone",
    )
    fun a_selected_photo_from_a_whatsapp_album_leaves_the_device() = behaviour {
        given {
            limitedMember()
            photos("WA")
            step("filed in a WhatsApp album", act = { library.placeIn("WhatsApp", "WA") }, until = { settled { true } })
        }

        whenever { library.changeSelection(listOf("WA")) }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("WA") } }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Under limited access the member's selection decides what a photo is",
        scenario = "A selected small image is shared",
    )
    fun a_selected_small_image_leaves_the_device() = behaviour {
        given {
            limitedMember()
            taken("SMALL", LibraryPhotoKind.LOW_RES)
        }

        whenever { library.changeSelection(listOf("SMALL")) }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("SMALL") } }
    }

    // ---- the range still decides when -------------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Under limited access the member's selection decides what a photo is",
        scenario = "A selected photo outside the range stays private",
    )
    fun a_selected_photo_from_before_the_range_stays() = behaviour {
        given {
            limitedMember()
            taken("BEFORE", date = BEFORE_THE_RANGE)
            photos("IN")
        }

        whenever { library.changeSelection(listOf("BEFORE", "IN")) }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("IN") }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("BEFORE") }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Under limited access the member's selection decides what a photo is",
        scenario = "Photos selected for an earlier event stay private",
    )
    fun photos_picked_for_an_earlier_event_stay() = behaviour {
        // The phone keeps the selection across events: what the member picked for an earlier one is still selected
        // when they join this one, and the range — not the selection — keeps it out.
        given {
            limitedMember()
            taken("EARLIER", date = AN_EARLIER_EVENT)
            taken("ITS_SCREENSHOT", LibraryPhotoKind.SCREENSHOT, date = AN_EARLIER_EVENT)
            photos("NOW")
        }

        whenever { library.changeSelection(listOf("EARLIER", "ITS_SCREENSHOT", "NOW")) }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("NOW") }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("EARLIER") }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("ITS_SCREENSHOT") }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Under limited access the member's selection decides what a photo is",
        scenario = "A selected undated photo is not shared",
    )
    fun a_selected_undated_photo_stays() = behaviour {
        given {
            limitedMember()
            taken("UNDATED", date = NO_CAPTURE_DATE)
            photos("DATED")
        }

        whenever { library.changeSelection(listOf("UNDATED", "DATED")) }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("DATED") }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("UNDATED") }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Under limited access the member's selection decides what a photo is",
        scenario = "A received photo in the selection is not shared back",
    )
    fun a_received_photo_in_the_selection_stays() = behaviour {
        given {
            limitedMember()
            step(
                "a member's photo on its way",
                act = {
                    publicBackend.foreignDevice(DEV_F, listOf(FOREIGN_ASSET))
                    os.foreground()
                },
                until = {
                    event<WorldEvent.DownloadStarted>()
                    settled { true }
                },
            )
            step("it arrived", act = { downloadSession.finish() }, until = {
                event<WorldEvent.LibraryAssetCreated> { it.asset == RECEIVED }
                settled { true }
            })
            photos("OWN")
        }

        // On iPhone what the app saves joins the selection; the member's own pick beside it leaves.
        whenever { library.changeSelection(listOf(RECEIVED, "OWN")) }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("OWN") }
            never<WorldEvent.SessionTransferCreated> { it.key != primaryKey("OWN") }
        }
    }

    // ---- switching between full and limited access ------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Switching between full and limited access re-uploads only what was de-selected",
        scenario = "A selected screenshot leaves on widening to full access",
    )
    fun widening_to_full_access_withdraws_a_selected_screenshot_and_sends_nothing_again() = behaviour {
        given { sharedUnderLimitedAccess() }

        whenever {
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.sharedTotal() == 1 }
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.UploadJobCreated>()
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Restoring or re-including a photo shares it again",
        scenario = "A screenshot withdrawn by full access returns with limited access",
    )
    fun a_screenshot_withdrawn_by_full_access_is_uploaded_again_under_limited_access() = behaviour {
        given {
            sharedUnderLimitedAccess()
            step(
                "widened to full access",
                act = {
                    library.permission(PhotoGrant.GRANTED)
                    os.foreground()
                },
                until = { screen<Layer.Joined> { it.sharedTotal() == 1 } },
            )
        }

        whenever {
            library.permission(PhotoGrant.LIMITED)
            library.changeSelection(listOf("CAM", "SHOT"))
        }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("SHOT") }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("CAM") }
            screen<Layer.Joined> { it.sharedTotal() == 2 }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** The photo [id], of [kind], captured at [date] (the event's day unless said), lands in the library. */
    private suspend fun Given.taken(id: String, kind: LibraryPhotoKind? = null, date: String = Rig.PHOTO_DATE) =
        step("$id taken", act = { library.add(id, kind, date) }, until = { settled { true } })

    /** A limited-access member who selected a camera photo and a screenshot, both shared and uploaded. */
    private suspend fun Given.sharedUnderLimitedAccess() {
        limitedMember()
        photos("CAM")
        taken("SHOT", LibraryPhotoKind.SCREENSHOT)
        selected("CAM", "SHOT")
        step("both uploaded, and the status refreshed", act = {
            uploadSession.complete()
            os.foreground()
        }, until = {
            screen<Layer.Joined> { it.sharedTotal() == 2 }
            settled { true }
        })
    }

    /** How many of the member's own photos the status says are shared — done or still going. */
    private fun Layer.Joined.sharedTotal(): Int? = (counts?.shared as? DirectionCount.Progress)?.total

    private companion object {
        /** A capture time before the default event's range (`Rig.WINDOW_START`). */
        const val BEFORE_THE_RANGE = "2026-05-01T10:00:00Z"

        /** A day of an earlier event this member shared to, weeks before this one's range. */
        const val AN_EARLIER_EVENT = "2026-04-12T15:00:00Z"

        /** A text the library cannot read as a time: a photo it holds no capture date for. */
        const val NO_CAPTURE_DATE = "undated"

        const val FOREIGN_ASSET = "FQ"

        /** The fellow member's photo, as this library saved it. */
        const val RECEIVED = "imported-$DEV_F-$FOREIGN_ASSET"
    }
}
