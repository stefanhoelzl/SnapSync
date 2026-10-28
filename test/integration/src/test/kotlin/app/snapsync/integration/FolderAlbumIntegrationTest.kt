package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.AlbumHolds
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RangeView
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **The event album where it is a folder** — an Android phone's (`openspec/specs/event-album`, `receiving-photos`,
 * `join-event`, `manage-membership`: their "On Android" scenarios), one scenario per test, over the real composed stack.
 *
 * What makes these Android's is one answer of the photo library's: its albums are folders ([androidLibrary]). The rest
 * is the app's own — which received photo it files into the album, what a member's emptied, renamed or deleted album
 * means, and how the join and settings surfaces explain the album. A received photo *arrives* when the library tells
 * the world it created it; it is *in the album* when the library tells the world it joined the album's folder; one
 * that joins no album stays in the camera folder, where the library saves what it is given no album for.
 */
class FolderAlbumIntegrationTest {

    // ---- other members' photos arrive in the photo library automatically ------------------------------------------

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Other members' photos arrive in the photo library automatically",
        scenario = "On Android, a received photo is in the camera roll",
    )
    fun with_the_album_off_a_received_photo_is_saved_into_no_album() = behaviour {
        given {
            androidLibrary()
            joinedEvent(Choices(saveToAlbum = false))
            aMembersPhotoOnItsWay(FIRST)
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == received(FIRST) }
            never<WorldEvent.LibraryJoinedAlbum>()
            never<WorldEvent.LibraryAlbumCreated>()
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Other members' photos arrive in the photo library automatically",
        scenario = "On Android with the album on, a received photo is in the event's album",
    )
    fun with_the_album_on_a_received_photo_is_saved_into_the_event_s_folder() = behaviour {
        val album = given {
            androidLibrary()
            val (_, album) = joinedSavingToAlbum()
            aMembersPhotoOnItsWay(FIRST)
            album
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == received(FIRST) }
            event<WorldEvent.LibraryJoinedAlbum> { it.album == album && it.asset == received(FIRST) }
        }
    }

    // ---- the album is created as soon as it can be ----------------------------------------------------------------

    @Test
    @Verifies(
        spec = "event-album",
        requirement = "The album is created as soon as it can be, under full or limited access",
        scenario = "On Android the album appears with its first received photo",
    )
    fun the_event_s_folder_holds_nothing_until_its_first_received_photo() = behaviour {
        given {
            androidLibrary()
            createdEvent(name = PARTY)
        }

        // Joined with the album on, and the member's own photo taken: the folder is made, and holds nothing of theirs.
        lateinit var created: Seen<WorldEvent.LibraryAlbumCreated>
        whenever {
            confirmJoin(Choices(saveToAlbum = true))
            takePhoto("OWN")
        }
        then {
            created = event<WorldEvent.LibraryAlbumCreated> { it.title == PARTY }
            never<WorldEvent.LibraryJoinedAlbum>()
        }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf(FIRST))
            os.foreground()
        }
        then { event<WorldEvent.DownloadStarted>() }

        whenever { downloadSession.finish() }
        then { event<WorldEvent.LibraryJoinedAlbum> { it.album == created.value.album && it.asset == received(FIRST) } }
    }

    // ---- turning the album off keeps it ---------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "event-album",
        requirement = "Turning the album off keeps it",
        scenario = "On Android, photos received while off go to the camera folder",
    )
    fun a_photo_received_with_the_album_turned_off_goes_to_the_camera_folder() = behaviour {
        given {
            androidLibrary()
            filledAlbum()
            step("the album turned off", act = { reconfigure(Choices(saveToAlbum = false)) }, until = {
                screen<Layer.Joined> { !it.membership.savesToAlbum && it.surface == JoinedSurface.Status }
                settled { true }
            })
            aMembersPhotoOnItsWay(FIRST, SECOND)
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == received(SECOND) }
            never<WorldEvent.LibraryJoinedAlbum>()
            never<WorldEvent.LibraryAlbumCreated>()
        }
    }

    // ---- the album survives leaving and is reused on rejoin -------------------------------------------------------

    @Test
    @Verifies(
        spec = "event-album",
        requirement = "The album survives leaving and is reused on rejoin",
        scenario = "On Android a renamed album is not followed",
    )
    fun a_photo_received_after_the_member_renamed_the_folder_goes_to_the_camera_folder() = behaviour {
        given {
            androidLibrary()
            val album = filledAlbum()
            step("renamed in a gallery app", act = { library.renameAlbum(album, "Renamed") }, until = {})
            aMembersPhotoOnItsWay(FIRST, SECOND)
        }

        // The renamed folder keeps its photo; the app follows no folder it did not make, and makes none.
        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == received(SECOND) }
            never<WorldEvent.LibraryJoinedAlbum>()
            never<WorldEvent.LibraryAlbumCreated>()
        }
    }

    // ---- a deleted album is recreated only when the member asks ---------------------------------------------------

    @Test
    @Verifies(
        spec = "event-album",
        requirement = "A deleted album is recreated only when the member asks",
        scenario = "On Android later photos arrive in the camera folder",
    )
    fun a_photo_received_after_the_member_deleted_the_folder_goes_to_the_camera_folder() = behaviour {
        given {
            androidLibrary()
            val album = filledAlbum()
            step("deleted in a gallery app", act = { library.deleteAlbum(album) }, until = {})
            aMembersPhotoOnItsWay(FIRST, SECOND)
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == received(SECOND) }
            never<WorldEvent.LibraryJoinedAlbum>()
            never<WorldEvent.LibraryAlbumCreated>()
        }
    }

    // ---- the album is offered on by default and declinable in one tap ---------------------------------------------

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen lets the member decide separately whether to share, whether to receive, and whether to keep an album",
        scenario = "The album choice on Android",
    )
    fun the_join_screen_offers_both_switches_the_range_and_the_folder_album_switched_on() = behaviour {
        given { androidLibrary() }

        whenever { createEvent(Rig.EVENT_NAME, Rig.WINDOW_START, Rig.WINDOW_END) }
        then {
            joinSurface { it.shareOn && it.receiveOn && it.saveToAlbum && it.albumHolds == AlbumHolds.RECEIVED }
        }
    }

    // What the album's note SAYS for the switches ("On Android the explanation names received photos only", "On Android
    // without receiving the album collects nothing", and iPhone's "The explanation follows the switches") is the join
    // screen's decision, not the core's. The screen state carries only the switches and how the library holds an album,
    // so the integration suite has no claim on them: what it hands the screen, the test above shows. `JoinScreenTest`
    // (`:ui:screens`) renders the note itself for each combination.

    // ---- the event's settings -------------------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Event settings change as they are made, without leaving, until the event's photos are deleted",
        scenario = "An Android membership from before the album opens with it off",
    )
    fun a_membership_stored_with_the_album_off_opens_its_settings_with_the_folder_album_off() = behaviour {
        // Before Android had the album, every Android membership was stored with it off, and none was migrated.
        given {
            androidLibrary()
            joinedEvent(Choices(saveToAlbum = false))
        }

        whenever { tap(UiIntent.OpenReconfigure) }
        then { settingsSurface { !it.saveToAlbum && it.albumHolds == AlbumHolds.RECEIVED } }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Settings explain what a change does",
        scenario = "On Android album-on mentions photos already received",
    )
    fun turning_the_folder_album_on_in_settings_is_explained_as_gathering_what_arrived() = behaviour {
        given {
            androidLibrary()
            joinedEvent(Choices(saveToAlbum = false))
            step("its settings open", act = { tap(UiIntent.OpenReconfigure) }, until = {
                settingsSurface { !it.saveToAlbum }
            })
        }

        // The settings' note is the screen's words for a folder album turned on: what it gathers, what stays.
        whenever { tap(UiIntent.SaveToAlbum(true)) }
        then { settingsSurface { it.saveToAlbum && it.albumHolds == AlbumHolds.RECEIVED } }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /**
     * Joined with the album on, and a fellow member's photo received into it — so the folder has held a photo, and a
     * later empty one is the member's doing. Answers the album.
     */
    private suspend fun Given.filledAlbum(): String {
        val (_, album) = joinedSavingToAlbum()
        aMembersPhotoOnItsWay(FIRST)
        step("it arrived in the album", act = { downloadSession.finish() }, until = {
            event<WorldEvent.LibraryJoinedAlbum> { it.album == album && it.asset == received(FIRST) }
            settled { true }
        })
        return album
    }

    /** A fellow member shares [assets], and the app has started downloading what it has not received yet. */
    private suspend fun Given.aMembersPhotoOnItsWay(vararg assets: String) = step(
        "${assets.joinToString()} shared by a member",
        act = {
            publicBackend.foreignDevice(DEV_F, assets.toList())
            os.foreground()
        },
        until = {
            event<WorldEvent.DownloadStarted>()
            settled { true }
        },
    )

    /** The join surface, its details loaded, showing a range [that] holds for. */
    private fun Expectations.joinSurface(that: (RangeView) -> Boolean) =
        screen<Layer.JoiningEvent> { layer ->
            layer.phase is JoinPhase.Detailed && layer.range?.let(that) == true
        }

    /** The event's settings, open over the joined screen, showing a range [that] holds for. */
    private fun Expectations.settingsSurface(that: (RangeView) -> Boolean) =
        screen<Layer.Joined> { layer -> (layer.surface as? JoinedSurface.Reconfigure)?.range?.let(that) == true }

    private companion object {
        const val PARTY = "Party"
        const val FIRST = "FQ"
        const val SECOND = "FR"

        /** The id the photo library gives the photo [DEV_F] shared as [asset]. */
        fun received(asset: String) = "imported-$DEV_F-$asset"
    }
}
