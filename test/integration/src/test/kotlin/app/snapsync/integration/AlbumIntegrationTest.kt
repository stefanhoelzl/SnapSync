package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.AlbumKind
import app.snapsync.levers.WorldEvent
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.ui.state.AlbumHolds
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun an_android_phone_is_offered_the_album_and_it_collects_received_photos_only() = rigTest {
        // An Android album is the folder received photos are saved into, so the join screen
        // offers it on, and it holds what arrives — never the member's own camera photo.
        androidLibrary()
        create(name = "Party")
        val gate = state().ui.layer as Layer.JoiningEvent
        assertEquals(AlbumHolds.RECEIVED, gate.range?.albumHolds)
        assertEquals(true, gate.range?.saveToAlbum, "the album is on by default, as on iPhone")

        join()
        awaitAlbum() // a download imports into the album only once it exists
        addPhoto("A")
        cycle()
        foreignDevice(DEV_F, "FQ")
        downloadAll()

        assertEquals(
            listOf(RECEIVED),
            albums().single().assets,
            "the received photo is in the album; the own one is not",
        )
        uploadAll()
        val shared = objects().mapTo(mutableSetOf()) { assetIdFromUploadKey(it).value }
        assertEquals(setOf("A"), shared, "the received photo in the album is not shared back, as on iPhone")
    }

    @Test
    @Verifies(
        spec = "event-album",
        requirement = "Turning the album on gathers what is already there",
        scenario = "On Android, turning the album on later moves received photos into it",
    )
    fun on_android_a_photo_received_with_the_album_off_is_gathered_when_it_is_turned_on() = rigTest {
        androidLibrary()
        createAndJoin(Choices(saveToAlbum = false))
        addPhoto("A")
        foreignDevice(DEV_F, "FQ")
        downloadAll()
        assertTrue(albums().isEmpty(), "no album while it is off: the photo is in the camera folder")

        reconfigure(Choices(saveToAlbum = true))
        awaitState { it.joined?.membership?.savesToAlbum == true }

        eventually<List<String>?>(read = { albums().singleOrNull()?.assets }) { it == listOf(RECEIVED) }
    }

    @Test
    @Verifies(spec = "event-album", requirement = "A deleted album is recreated only when the member asks")
    fun on_android_a_deleted_album_stays_deleted_until_the_member_turns_it_on_again() = rigTest {
        androidLibrary()
        createAndJoin(Choices(saveToAlbum = true), name = "Party")
        awaitAlbum()
        foreignDevice(DEV_F, "FQ")
        downloadAll()
        val first = albums().single()
        assertEquals(listOf(RECEIVED), first.assets)

        library.deleteAlbum(first.id) // the gallery app deletes the folder, and its photo with it
        foreignDevice(DEV_F, "FR")
        downloadAll()
        assertEquals(listOf(first.id), albums().map { it.id }, "no album was brought back")
        assertTrue(albums().single().assets.isEmpty(), "the later photo arrived in the camera folder")

        reconfigure(Choices(saveToAlbum = false))
        awaitState { it.joined?.membership?.savesToAlbum == false }
        reconfigure(Choices(saveToAlbum = true))
        awaitState { it.joined?.membership?.savesToAlbum == true }

        eventually<List<String>?>(read = {
            albums().firstOrNull { it.id != first.id }?.assets
        }) { it == listOf("imported-$DEV_F-FR") }
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
    fun neither_a_save_nor_a_join_waits_for_the_gather_it_starts() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin(Choices(saveToAlbum = false))
        addPhoto("A")
        uploadAll()
        library.holdAdds(on = true)

        // If the Save awaited the gather, it would never land: every add is held.
        reconfigure(Choices(saveToAlbum = true))
        awaitState { it.joined?.membership?.savesToAlbum == true }
        assertTrue(awaitAlbum().single().assets.isEmpty(), "the Save landed with its gather still held")
        library.holdAdds(on = false)
        eventually(read = { albums().single().assets }) { it == listOf("A") }

        // A join's gather: a photo received in this event, whose import is permanent across a leave.
        reconfigure(Choices(saveToAlbum = false))
        awaitState { it.joined?.membership?.savesToAlbum == false }
        foreignDevice(DEV_F, "FQ")
        downloadAll()
        leave()
        library.holdAdds(on = true)
        val placed = albums().single().assets.size

        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.eventId == event }
        join(Choices(saveToAlbum = true)) // returns once joined

        assertEquals(placed, albums().single().assets.size, "the join landed with its gather still held")
        library.holdAdds(on = false)
        eventually<Int>(read = { albums().single().assets.size }) { it > placed }
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

    /** The photo library plays an Android phone's, whose albums are folders — read once at host assembly. */
    private suspend fun Rig.androidLibrary() {
        library.albumKind(AlbumKind.FOLDER)
        host.relaunch()
    }

    private companion object {
        /** The id the photo library gives the photo [DEV_F] shared as FQ. */
        const val RECEIVED = "imported-$DEV_F-FQ"
    }
}
