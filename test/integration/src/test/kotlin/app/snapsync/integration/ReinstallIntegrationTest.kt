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

    /**
     * Open the event's link and confirm its gate with [choices], as a member rejoining after a reinstall does: the
     * confirm raises the photo-access dialog (the reinstall reset the grant), the join goes ahead without waiting for
     * it, and the member answers it only afterwards — the order measured on the SE2.
     */
    private suspend fun Rig.rejoin(event: String, vararg choices: Pair<String, String>) {
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.eventId == event }
        join(*choices)
        val before = adoptions()
        permission("GRANTED")
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
