package app.snapsync.mock

import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A force-quit cancels the session's transfers as iOS does: they are gone at once, the session's record of them stays,
 * and their ends are reported only when the session is next brought up.
 */
class UploadSessionForceQuitTest {

    @Test
    fun a_force_quit_leaves_no_transfer_live() = runTest {
        val device = MockDevice()
        val target = UploadTarget("https://upload.test/a", emptyMap(), TransferNetwork.ANY)
        assertEquals(
            CreateOutcome.CREATED,
            device.uploadSession.port().create(TransferSource.File("a.jpg"), target, "A1"),
        )

        device.uploadSession.forceQuit()

        assertEquals(emptyList(), device.uploadSession.view().live)
        assertEquals(listOf("A1"), device.uploadSession.view().created, "what was created stays the session's record")
    }
}
