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
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * The event album over the real stack, read back off the photo library: an opted-in
 * member's own photos are placed when their upload is first enqueued, one album per event however often it is
 * ensured, and neither a Save nor a join waits for the gather it starts.
 *
 * What a gather admits — carried-over and received photos, a failed union read, the first permission emission — is
 * `AlbumGatherTest`'s (`:test:feature`); turning the album on in place is `ReconfigureIntegrationTest`'s.
 */
class AlbumIntegrationTest {

    @Test
    @Verifies(spec = "event-album", requirement = "The album holds the event as this device holds it")
    fun enqueued_uploads_are_placed_in_the_album_before_they_finish_and_only_once() = behaviour {
        val (_, album) = given {
            extensionUploadsOnly()
            val joined = joinedSavingToAlbum(name = "Party") // a cycle places only into an album that exists
            step(
                "two photos taken",
                act = {
                    library.add("A")
                    library.add("B")
                },
                until = { settled { true } },
            )
            joined
        }

        // Placed, then the jobs are created — and no job has landed.
        whenever { cycle() }
        then {
            event<WorldEvent.LibraryJoinedAlbum> { it.album == album && it.asset == "A" }
            event<WorldEvent.LibraryJoinedAlbum> { it.album == album && it.asset == "B" }
            settled { true }
        }

        // Acknowledged: a completion places nothing a second time.
        whenever {
            uploadQueue.completeJob(null)
            cycle()
        }
        then { never<WorldEvent.LibraryJoinedAlbum>() }
    }

    @Test
    @Verifies(spec = "event-album", requirement = "The album is offered on by default and declinable in one tap")
    @Verifies(
        spec = "event-album",
        requirement = "The album holds the event as this device holds it",
        scenario = "On Android only received photos are in the album",
    )
    @Verifies(
        spec = "photo-sharing",
        requirement = "On Android under full access, only photos in the DCIM folder are shared",
        scenario = "The event album's photos are not shared",
    )
    fun an_android_phone_is_offered_the_album_and_it_collects_received_photos_only() = behaviour {
        // An Android album is the folder received photos are saved into, so the join screen
        // offers it on, and it holds what arrives — never the member's own camera photo.
        given { androidLibrary() }

        whenever { createEvent("Party", Rig.WINDOW_START, Rig.WINDOW_END) }
        then {
            screen<Layer.JoiningEvent> { gate ->
                gate.phase is JoinPhase.Detailed &&
                    gate.range?.albumHolds == AlbumHolds.RECEIVED &&
                    gate.range?.saveToAlbum == true // the album is on by default, as on iPhone
            }
        }

        // A download imports into the album only once it exists.
        lateinit var album: Seen<WorldEvent.LibraryAlbumCreated>
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            album = event<WorldEvent.LibraryAlbumCreated>()
            settled { true }
        }

        whenever {
            takePhoto("A")
            cycle()
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            os.foreground()
        }
        then {
            event<WorldEvent.DownloadStarted>()
            never<WorldEvent.LibraryJoinedAlbum>()
            settled { true }
        }

        // The received photo is in the album; the own one is not.
        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryJoinedAlbum> { it.album == album.value.album && it.asset == RECEIVED }
            settled { it.state.albums?.singleOrNull()?.photos == listOf(RECEIVED) }
        }

        // The received photo in the album is not shared back, as on iPhone.
        whenever { uploadAll() }
        then {
            event<WorldEvent.BytesStored> { it.asset == "A" }
            never<WorldEvent.BytesStored> { it.asset != "A" }
            never<WorldEvent.UploadJobCreated> { it.job.startsWith(RECEIVED) }
            never<WorldEvent.ManifestPublished> { RECEIVED in it.added }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "event-album",
        requirement = "Turning the album on gathers what is already there",
        scenario = "On Android, turning the album on later moves received photos into it",
    )
    fun on_android_a_photo_received_with_the_album_off_is_gathered_when_it_is_turned_on() = behaviour {
        given {
            androidLibrary()
            joinedEvent(Choices(saveToAlbum = false))
            photos("A")
            // No album while it is off: the photo is in the camera folder.
            receivedFrom(DEV_F, "FQ", into = { settled { it.state.albums.orEmpty().isEmpty() } })
        }

        whenever { reconfigure(Choices(saveToAlbum = true)) }
        then {
            screen<Layer.Joined> { it.membership.savesToAlbum }
            event<WorldEvent.LibraryJoinedAlbum> { it.asset == RECEIVED }
            settled { it.state.albums?.singleOrNull()?.photos == listOf(RECEIVED) }
        }
    }

    @Test
    @Verifies(spec = "event-album", requirement = "A deleted album is recreated only when the member asks")
    fun on_android_a_deleted_album_stays_deleted_until_the_member_turns_it_on_again() = behaviour {
        val first = given {
            androidLibrary()
            val (_, album) = joinedSavingToAlbum(name = "Party")
            receivedFrom(DEV_F, "FQ", into = {
                event<WorldEvent.LibraryJoinedAlbum> { it.album == album && it.asset == RECEIVED }
            })
            album
        }

        // The gallery app deletes the folder, and its photo with it; a later photo arrives in the camera folder.
        whenever {
            library.deleteAlbum(first)
            publicBackend.foreignDevice(DEV_F, listOf("FR"))
            os.foreground()
        }
        then {
            event<WorldEvent.DownloadStarted>()
            settled { true }
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == LATER }
            never<WorldEvent.LibraryAlbumCreated>() // no album was brought back
            never<WorldEvent.LibraryJoinedAlbum>()
            settled { it.state.albums.orEmpty().all { album -> album.id == first && album.photos.isEmpty() } }
        }

        whenever { reconfigure(Choices(saveToAlbum = false)) }
        then {
            screen<Layer.Joined> { !it.membership.savesToAlbum }
            settled { true }
        }

        whenever { reconfigure(Choices(saveToAlbum = true)) }
        then {
            screen<Layer.Joined> { it.membership.savesToAlbum }
            event<WorldEvent.LibraryAlbumCreated> { it.album != first }
            event<WorldEvent.LibraryJoinedAlbum> { it.album != first && it.asset == LATER }
            settled { it.state.albums?.firstOrNull { album -> album.id != first }?.photos == listOf(LATER) }
        }
    }

    @Test
    @Verifies(
        spec = "event-album",
        requirement = "The album survives leaving and is reused on rejoin",
        scenario = "Rejoin reuses the album",
    )
    fun rejoining_the_event_reuses_its_album() = behaviour {
        given {
            val (event, _) = joinedSavingToAlbum(name = "Party")
            left()
            gateOpenOn(event)
        }

        whenever { confirmJoin(Choices(saveToAlbum = true)) }
        then {
            screen<Layer.Joined>()
            never<WorldEvent.LibraryAlbumCreated>() // the stored album is reused, never duplicated
        }
    }

    @Test
    @Verifies(spec = "event-album", requirement = "Turning the album on gathers what is already there")
    fun neither_a_save_nor_a_join_waits_for_the_gather_it_starts() = behaviour {
        val event = given {
            extensionUploadsOnly()
            joinedEvent(Choices(saveToAlbum = false)).also {
                photos("A")
                uploaded("A")
                step("every add to an album held", act = { library.holdAdds(on = true) }, until = {})
            }
        }

        // If the Save awaited the gather, it would never land: every add is held.
        whenever { reconfigure(Choices(saveToAlbum = true)) }
        then {
            screen<Layer.Joined> { it.membership.savesToAlbum }
            event<WorldEvent.LibraryAddHeld>()
            never<WorldEvent.LibraryJoinedAlbum>()
            settled { it.state.albums?.singleOrNull()?.photos?.isEmpty() == true } // landed with its gather still held
        }

        whenever { library.holdAdds(on = false) }
        then {
            event<WorldEvent.LibraryJoinedAlbum> { it.asset == "A" }
            settled { it.state.albums?.singleOrNull()?.photos == listOf("A") }
        }

        // A join's gather: a photo received in this event, whose import is permanent across a leave.
        whenever { reconfigure(Choices(saveToAlbum = false)) }
        then {
            screen<Layer.Joined> { !it.membership.savesToAlbum }
            settled { true }
        }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            os.foreground()
        }
        then {
            event<WorldEvent.DownloadStarted>()
            settled { true }
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == RECEIVED }
            never<WorldEvent.LibraryJoinedAlbum>()
            settled { true }
        }

        whenever { tap(UiIntent.LeaveEvent) }
        then {
            event<WorldEvent.MemberLeft>()
            settled { true }
        }

        whenever {
            library.holdAdds(on = true)
            openLink(inviteLink(event))
        }
        then { screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed } }

        whenever { confirmJoin(Choices(saveToAlbum = true)) }
        then {
            screen<Layer.Joined>()
            event<WorldEvent.LibraryAddHeld>()
            never<WorldEvent.LibraryJoinedAlbum>()
            settled { it.state.albums?.singleOrNull()?.photos == listOf("A") } // landed with its gather still held
        }

        whenever { library.holdAdds(on = false) }
        then {
            event<WorldEvent.LibraryJoinedAlbum> { it.asset == RECEIVED }
            settled { it.state.albums?.singleOrNull()?.photos?.toSet() == setOf("A", RECEIVED) }
        }
    }

    @Test
    @Verifies(
        spec = "event-album",
        requirement = "The album survives leaving and is reused on rejoin",
        scenario = "A renamed album keeps receiving photos",
    )
    fun an_album_the_member_renamed_still_takes_the_photos_that_arrive() = behaviour {
        val (_, album) = given { joinedSavingToAlbum(name = "Party") }

        whenever {
            library.renameAlbum(album, "Best party")
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            os.foreground()
        }
        then { event<WorldEvent.DownloadStarted>() }

        whenever { downloadSession.finish() }
        then { event<WorldEvent.LibraryJoinedAlbum> { it.album == album && it.asset == RECEIVED } }
    }

    @Test
    @Verifies(
        spec = "event-album",
        requirement = "Album problems never affect sharing or receiving",
        scenario = "A placement fails",
    )
    fun a_received_photo_whose_album_is_gone_is_still_saved() = behaviour {
        val (_, album) = given { joinedSavingToAlbum(name = "Party") }

        whenever {
            library.deleteAlbum(album)
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            os.foreground()
        }
        then { event<WorldEvent.DownloadStarted>() }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == RECEIVED }
            never<WorldEvent.LibraryJoinedAlbum>()
            never<WorldEvent.LibraryAlbumCreated>() // a deleted album comes back only when the member asks
        }
    }

    @Test
    @Verifies(
        spec = "event-album",
        requirement = "Album problems never affect sharing or receiving",
        scenario = "A placement fails",
    )
    fun an_own_photo_whose_album_is_gone_is_still_uploaded() = behaviour {
        val (_, album) = given { joinedSavingToAlbum(name = "Party") }

        whenever {
            library.deleteAlbum(album)
            takePhoto("A")
            os.foreground()
        }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
            never<WorldEvent.LibraryJoinedAlbum>()
        }

        // Its bytes land: shared, with nothing left to upload. Not judged by a manifest publish — the manifest declared
        // the photo when the walk found it, and a finished upload changes nothing it lists, so whether this act
        // publishes again depends only on how many walks came before it.
        whenever {
            uploadSession.complete()
            os.foreground()
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    /** This device leaves its event. */
    private suspend fun Given.left() = step("left", act = { tap(UiIntent.LeaveEvent) }, until = { settled { true } })

    /** The join gate open on [event], through its invite, ready to join. */
    private suspend fun Given.gateOpenOn(event: String) {
        val link = inviteFor(event)
        step(
            "its join gate open",
            act = { openLink(link) },
            until = { screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed } },
        )
    }

    /**
     * [device] shares [asset], the app in front starts its download, and the photo arrives in the library — and [into]
     * holds of where it arrived.
     */
    private suspend fun Given.receivedFrom(device: String, asset: String, into: Expectations.() -> Unit) {
        downloadStarted(device, asset)
        step("$asset arrived", act = { downloadSession.finish() }, until = {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported(asset, device) }
            into()
            settled { true }
        })
    }

    private companion object {
        /** The id the photo library gives the photo [DEV_F] shared as FQ. */
        const val RECEIVED = "imported-$DEV_F-FQ"

        /** The id the photo library gives the photo [DEV_F] shared later, as FR. */
        const val LATER = "imported-$DEV_F-FR"
    }
}
