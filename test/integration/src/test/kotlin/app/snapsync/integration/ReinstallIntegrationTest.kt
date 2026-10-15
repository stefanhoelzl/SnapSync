package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.feature.status.NetworkWatch
import app.snapsync.levers.AlbumKind
import app.snapsync.levers.NetworkState
import app.snapsync.levers.PhotoGrant
import app.snapsync.model.Direction
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deleting the app and installing it again: the download record goes with the app, and the SnapSync mark on each
 * received photo's name is what the rejoin recognises it by.
 *
 * `device/reinstall` deletes the app's files, databases, user defaults and photo grant and keeps what outlives it —
 * the Keychain, the photo library, the backend — then launches the app again, unjoined.
 */
class ReinstallIntegrationTest {

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "A deleted received photo never comes back",
        scenario = "A reinstall recognises received photos",
    )
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A reinstall does not share received photos back",
    )
    fun a_rejoin_after_a_reinstall_neither_receives_nor_shares_back_what_the_library_still_holds() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        foreignDevice(OTHER, "FA", "FB")
        downloadAll()
        val received = libraryTotal()
        val deleted = receivedAssetIds().first()
        library.remove(deleted)

        host.reinstall()
        rejoin(event)
        downloadAll()

        assertEquals(received, libraryTotal(), "the photo still held is not received again; the deleted one may be")
        cycle()
        assertEquals(0, jobs().created, "no received photo is shared back as the member's own")
        assertTrue(objects().isEmpty(), "and nothing of them lands on the backend")
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A share-only rejoin does not share received photos back",
    )
    fun a_share_only_rejoin_after_a_reinstall_does_not_share_received_photos_back() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        foreignDevice(OTHER, "FA", "FB")
        downloadAll()

        host.reinstall()
        rejoin(event, Choices(direction = Direction.UploadOnly))

        cycle()
        assertEquals(0, jobs().created, "a share-only member shares none of the photos it received")
        assertTrue(objects().isEmpty())
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "A deleted received photo never comes back",
        scenario = "A reinstall recognises received photos",
    )
    fun an_android_rejoin_recognises_the_received_photos_filed_into_the_event_album() = rigTest {
        // An Android phone's albums are folders, and its event album's folder is no sharing candidate — so the
        // library read that recognises received photos must reach into it.
        library.albumKind(AlbumKind.FOLDER)
        host.relaunch()
        val event = createAndJoin(Choices(saveToAlbum = true))
        awaitAlbum() // a download imports into the album only once it exists
        foreignDevice(OTHER, "FA", "FB")
        downloadAll()
        eventually(read = { albums().singleOrNull()?.assets?.size }) { it == 2 }
        val received = libraryTotal()

        host.reinstall()
        rejoin(event, Choices(saveToAlbum = true))
        downloadAll()

        assertEquals(received, libraryTotal(), "the album's received photos are recognised, not received again")
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "On Android a reinstall never shares the album's photos back",
    )
    @Verifies(
        spec = "photo-sharing",
        requirement = "On Android under full access, only photos in the DCIM folder are shared",
        scenario = "An own photo moved into the event album is shared",
    )
    fun an_android_reinstall_shares_no_received_album_photo_back_but_an_own_photo_put_there() = rigTest {
        // The event album's folder is a folder of the default gallery like any other (capability `photo-sharing`): a
        // received photo there is kept out by its mark after a reinstall, as on iPhone — not by its folder — and a photo
        // the member put there themselves is shared.
        extensionUploadsOnly()
        library.albumKind(AlbumKind.FOLDER)
        host.relaunch()
        val event = createAndJoin(Choices(saveToAlbum = true))
        awaitAlbum()
        foreignDevice(OTHER, "FA", "FB")
        downloadAll()
        val album = eventually(read = { albums().singleOrNull() }) { it?.assets?.size == 2 }!!

        host.reinstall()
        addPhoto("OWN")
        library.placeIn(album.id, "OWN")
        rejoin(event, Choices(saveToAlbum = true))
        uploadAll()

        assertEquals(setOf("OWN"), objects().mapTo(mutableSetOf()) { assetIdFromUploadKey(it).value })
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A rejoin that cannot reach the event shares nothing yet",
    )
    fun a_rejoin_that_cannot_read_the_event_shares_nothing_until_it_can() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        foreignDevice(OTHER, "FA", "FB")
        downloadAll()

        host.reinstall()
        addPhoto("OWN")
        backend.failUnion(true)
        rejoinUnrecognised(event)

        cycle()
        assertEquals(0, jobs().created, "nothing is shared before the event's photos have been read")
        assertTrue(manifest(event).isNullOrEmpty(), "and nothing is announced to the others")
        assertEquals(SyncHealth.Loading, state().joined?.health, "the status stays on its neutral line")

        backend.failUnion(false)
        recognisedAfter { os.foreground() }
        uploadAll()

        assertEquals(
            setOf("OWN"),
            objects().mapTo(mutableSetOf()) { assetIdFromUploadKey(it).value },
            "only the own photo",
        )
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A rejoin that cannot reach the event shares nothing yet",
    )
    fun a_background_wake_retries_the_read_a_rejoin_could_not_make() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        foreignDevice(OTHER, "FA")
        downloadAll()

        host.reinstall()
        backend.failUnion(true)
        rejoinUnrecognised(event)
        os.background()
        backend.failUnion(false)

        recognisedAfter { os.backgroundTask(HEARTBEAT, awaitRelease = true) }
        cycle()
        assertEquals(0, jobs().created, "the received photo is recognised, not shared")
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A rejoin that cannot reach the event shares nothing yet",
    )
    fun the_network_returning_retries_the_read_a_rejoin_could_not_make() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        foreignDevice(OTHER, "FA")
        downloadAll()

        host.reinstall()
        backend.failUnion(true)
        rejoinUnrecognised(event)
        network.set(NetworkState.OFFLINE)
        // A missing network is published only once it has lasted the watch's grace — real time on this host.
        awaitHealth(timeout = NetworkWatch.DEFAULT_GRACE * 3) { it is SyncHealth.NoNetwork }
        backend.failUnion(false)

        recognisedAfter { network.set(NetworkState.ONLINE) }
        cycle()
        assertEquals(0, jobs().created, "the received photo is recognised, not shared")
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "Received photos added to a limited selection are not shared back",
    )
    fun received_photos_added_to_a_limited_selection_later_are_recognised_not_shared() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        foreignDevice(OTHER, "FA", "FB")
        downloadAll()
        val received = receivedAssetIds()

        // Reinstalled, and rejoined under a partial grant that selects only the member's own photo.
        host.reinstall()
        addPhoto("OWN")
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.eventId == event }
        join()
        permission(PhotoGrant.LIMITED)
        devControls.pin(app = true, extension = true)
        selected("OWN")
        foreground()
        awaitAppUploads(1)

        // Later the member adds the received photos — still carrying their mark — to the selection.
        val before = adoptions()
        selected("OWN", *received.toTypedArray())
        eventually(read = { adoptions() }) { it > before }
        foreground()

        assertEquals(listOf(primaryKey("OWN")), appUploads().live, "only the own photo is shared")
        assertEquals(1, appUploads().created, "no received photo became a transfer")
        assertEquals(setOf("OWN"), manifest(event)?.keys, "and none is announced to the others")
    }

    /** The member's selection becomes exactly [ids], and the app has read it. */
    private suspend fun Rig.selected(vararg ids: String) {
        library.changeSelection(ids.toList())
        eventually(read = { gallery().policy?.assets?.mapTo(mutableSetOf()) { it.assetId } }) { it == ids.toSet() }
    }

    /**
     * The rejoin of [rejoin], when its recognition cannot read the event. The foreground's tail is awaited: its import
     * asks for recognition too, and one still running when a test lets the event be read again would recognise the
     * photos itself — leaving the pass the test triggers next nothing to do.
     */
    private suspend fun Rig.rejoinUnrecognised(event: String) {
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.eventId == event }
        join()
        permission(PhotoGrant.GRANTED)
        foreground()
        eventually(read = { UNRECOGNISED.findAll(host.logs()).count() }) { it >= 1 }
    }

    /** Run [trigger], then wait until a recognition pass has read the event and recorded what it found. */
    private suspend fun Rig.recognisedAfter(trigger: suspend Rig.() -> Unit) {
        val before = adoptions()
        trigger()
        eventually(read = { adoptions() }) { it > before }
    }

    /**
     * Open the event's link and confirm its gate with [choices], as a member rejoining after a reinstall does: the
     * confirm raises the photo-access dialog (the reinstall reset the grant), the join goes ahead without waiting for
     * it, and the member answers it only afterwards — the order measured on the SE2.
     */
    private suspend fun Rig.rejoin(event: String, choices: Choices = Choices()) {
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.eventId == event }
        join(choices)
        // The downloads the rejoin planned finish while the dialog is still open, as they did on the phone.
        downloadSession.finish()
        val before = adoptions()
        // Closing the dialog makes the app active: iOS delivers the foreground right after the grant, and on the SE2
        // its import ran while the grant's adoption was still reading names (2026-10-01). Every import drain now waits
        // for the membership's adoption (`DownloadControllerTest` pins that order; here the mock's reads are instant).
        permission(PhotoGrant.GRANTED)
        os.foreground()
        // The grant's adoption is the app's answer to it, and it runs before the uploads are armed; a cycle forced
        // here directly (as a test may) must not overtake it, as none can on a phone.
        eventually(read = { adoptions() }) { it > before }
    }

    /** How many adoption passes the app has logged — each join's, and each usable grant's. */
    private suspend fun Rig.adoptions(): Int = ADOPTION.findAll(host.logs()).count()

    /** The library's photos whose names carry the SnapSync mark — every received photo, by asset id. */
    private suspend fun Rig.receivedAssetIds(): List<String> =
        gallery(resources = true).policy?.assets.orEmpty()
            .filter { a -> a.originalFilenames.orEmpty().any { ReceivedPhotoName.tokenOf(it) != null } }
            .map { it.assetId }

    private companion object {
        const val OTHER = DEV_F
        val ADOPTION = Regex("""adopted \d+ received photo|recognised: nothing open""")
        val UNRECOGNISED = Regex("""union unavailable — nothing recognised""")
        const val HEARTBEAT = "app.snapsync.upload.heartbeat"
    }
}
