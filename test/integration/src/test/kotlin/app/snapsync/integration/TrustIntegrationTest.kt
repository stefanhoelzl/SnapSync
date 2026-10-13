package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.model.DeviceRefusal
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The trust promises a member relies on without seeing them, over the REAL composed core, driven through the control
 * protocol: who may change an event, that a credential problem loses no photo, that an outdated app neither shares
 * nor receives until it is updated, and that a reinstalled phone stays the same device.
 */
class TrustIntegrationTest {

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "Only a genuine SnapSync app can change an event",
        scenario = "An Android phone with an unlocked bootloader",
    )
    fun a_phone_the_service_refuses_cannot_join_and_the_event_is_unchanged() = rigTest {
        val event = registerEvent()
        foreignDevice(OTHER, "FA")
        val before = union(event)

        device("backend/refuse-attestation", "reason" to DeviceRefusal.DEVICE_MODIFIED.wireName)
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed }
        user("confirmJoin")
        awaitState { state ->
            ((state.ui.layer as? Layer.JoiningEvent)?.phase as? JoinPhase.Detailed)?.step ==
                JoinPhase.Detailed.Step.DeviceRefused
        }

        assertEquals(before, union(event), "the event the refused phone tried to join is unchanged")
        assertTrue(state().ready.configResolved.not(), "and the phone is in no event")
    }

    @Test
    @Verifies(
        spec = "app-update-required",
        requirement = "Nothing is shared or received while the app is outdated",
        scenario = "Photos wait for the update",
    )
    fun photos_taken_while_outdated_are_shared_after_the_update() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        device("backend/min-app-version", "minimum" to "0.4")
        device("app-version", "version" to "0.3")
        reconcile()
        awaitState { it.ui.layer is Layer.UpdateRequired }

        addPhoto("P1")
        // A fellow member shares meanwhile (through the backend's public surface, as a build the backend serves).
        device("app-version", "version" to "0.4")
        foreignDevice(OTHER, "FA")
        device("app-version", "version" to "0.3")
        cycle()
        completeJobs()
        foreground()
        // Read while still outdated, before the update: the backend's public listing refuses an outdated build too.
        assertTrue(downloadedNothing(), "an outdated app receives nothing")

        device("app-version", "version" to "0.4")
        assertTrue(primaryKey("P1") !in objects(), "and shared nothing while it was outdated")
        device("app-version", "version" to "0.3")

        device("app-version", "version" to "0.4")
        reconcile()
        awaitState { it.ui.layer !is Layer.UpdateRequired }
        eventually(read = { uploadAll(); objects() }) { held: Set<String> -> primaryKey("P1") in held }
        downloadAll()
        assertEquals(2L, libraryTotal(), "and the fellow member's photo arrives once it is served again")
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "No account and no personal identity",
        scenario = "Reinstalling on the same phone",
    )
    fun a_reinstalled_phone_is_known_by_the_same_device_identifier() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        addPhoto("P1")
        uploadAll()
        assertTrue(primaryKey("P1") in objects(), "the first install's photo is filed under this phone's identifier")

        device("reinstall")
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.eventId == event }
        join()
        permission("GRANTED")
        foreground()
        addPhoto("P2")

        // `objects()` lists what the backend holds under the identifier the phone presents NOW: the second install's
        // photo lands beside the first install's, so the phone is the same device to its event.
        val held = eventually(read = { uploadAll(); objects() }) { held: Set<String> -> primaryKey("P2") in held }
        assertTrue(primaryKey("P1") in held, "the reinstalled phone presents the identifier it had before")
    }

    private suspend fun Rig.downloadedNothing(): Boolean = libraryTotal() == 1L

    private companion object {
        const val OTHER = DEV_F
    }
}
