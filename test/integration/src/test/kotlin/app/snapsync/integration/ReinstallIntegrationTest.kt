package app.snapsync.integration

import app.snapsync.model.Layer
import app.snapsync.model.ReceivedPhotoName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deleting the app and installing it again (capability `receiving-photos`, "A deleted received photo never comes back"
 * and "Received photos are never shared back"): the download record goes with the app, and the SnapSync mark on each
 * received photo's name is what the rejoin recognises it by.
 *
 * `device/reinstall` deletes the app's files, databases, user defaults and photo grant and keeps what outlives it —
 * the Keychain, the photo library, the backend — then launches the app again, unjoined.
 */
class ReinstallIntegrationTest {

    @Test
    fun a_rejoin_after_a_reinstall_neither_receives_nor_shares_back_what_the_library_still_holds() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        foreignDevice(OTHER, "FA", "FB")
        downloadAll()
        val received = libraryTotal()
        val deleted = receivedAssetIds().first()
        device("gallery/remove", "id" to deleted)

        device("reinstall")
        rejoin(event)
        downloadAll()

        assertEquals(received, libraryTotal(), "the photo still held is not received again; the deleted one may be")
        cycle()
        assertEquals(0, jobs().created, "no received photo is shared back as the member's own")
        assertTrue(objects().isEmpty(), "and nothing of them lands on the backend")
    }

    @Test
    fun a_share_only_rejoin_after_a_reinstall_does_not_share_received_photos_back() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        foreignDevice(OTHER, "FA", "FB")
        downloadAll()

        device("reinstall")
        rejoin(event, "direction" to "upload")

        cycle()
        assertEquals(0, jobs().created, "a share-only member shares none of the photos it received")
        assertTrue(objects().isEmpty())
    }

    @Test
    fun an_android_rejoin_recognises_the_received_photos_filed_into_the_event_album() = rigTest {
        // An Android phone's albums are folders, and its event album's folder is no sharing candidate — so the
        // library read that recognises received photos must reach into it (capability `receiving-photos`).
        device("album/kind", "kind" to "folder")
        device("relaunch")
        val event = createAndJoin("saveToAlbum" to "true")
        foreignDevice(OTHER, "FA", "FB")
        downloadAll()
        eventually(read = { albums().singleOrNull()?.assets?.size }) { it == 2 }
        val received = libraryTotal()

        device("reinstall")
        rejoin(event, "saveToAlbum" to "true")
        downloadAll()

        assertEquals(received, libraryTotal(), "the album's received photos are recognised, not received again")
    }

    /**
     * Open the event's link and confirm its gate with [choices], as a member rejoining after a reinstall does: the
     * confirm raises the photo-access dialog (the reinstall reset the grant), the join goes ahead without waiting for
     * it, and the member answers it only afterwards — the order measured on the SE2.
     */
    private suspend fun Rig.rejoin(event: String, vararg choices: Pair<String, String>) {
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.eventId == event }
        join(*choices)
        // The downloads the rejoin planned finish while the dialog is still open, as they did on the phone.
        device("downloads/stage")
        val before = adoptions()
        // Closing the dialog makes the app active: iOS delivers the foreground right after the grant, and on the SE2
        // its import ran while the grant's adoption was still reading names (2026-10-01). Every import drain now waits
        // for the membership's adoption (`DownloadControllerTest` pins that order; here the mock's reads are instant).
        permission("GRANTED")
        os("app", "onForeground")
        // The grant's adoption is the app's answer to it, and it runs before the uploads are armed; a cycle forced
        // here directly (as a test may) must not overtake it, as none can on a phone.
        eventually(read = { adoptions() }) { it > before }
    }

    /** How many adoption passes the app has logged — each join's, and each usable grant's. */
    private suspend fun Rig.adoptions(): Int = ADOPTION.findAll(client.logs()).count()

    /** The library's photos whose names carry the SnapSync mark — every received photo, by asset id. */
    private suspend fun Rig.receivedAssetIds(): List<String> =
        gallery(resources = true).policy?.assets.orEmpty()
            .filter { a -> a.originalFilenames.orEmpty().any { ReceivedPhotoName.tokenOf(it) != null } }
            .map { it.assetId }

    private companion object {
        const val OTHER = "DEV-F"
        val ADOPTION = Regex("""adopted \d+ received photo""")
    }
}
