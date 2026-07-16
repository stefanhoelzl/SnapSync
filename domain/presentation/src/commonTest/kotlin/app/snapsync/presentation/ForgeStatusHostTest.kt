package app.snapsync.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * The forge factory behind `SNAPSYNC_FORGE_STATE` (capability `ios-app-shell`). Each recognized state
 * must reduce — through the REAL [StatusContainerHost], from forged sources only — to the intended
 * frame, with no backend, attestation token, or photo-library access (the factory constructs neither,
 * so a passing test proves their absence). An unrecognized name must be rejected so the shell can fall
 * back to the live stack.
 */
class ForgeStatusHostTest {

    @Test
    fun `create forges the create landing screen`() = runTest {
        val host = assertNotNull(forgeStatusHost("create", backgroundScope))
        // Config absent + idle creation → the create input, exactly as production reduces it.
        assertEquals(UiState.CreateEvent(error = null), host.container.stateFlow.value)
    }

    @Test
    fun `joining forges the joined layer with a static non-pulsing upload arrow and the invite QR`() =
        runTest {
            val host = assertNotNull(forgeStatusHost("joining", backgroundScope))
            // synced (12) < total (47), nothing in flight → STATIC, deterministic (no animation to
            // race the capture); download arm masked to HIDDEN by the empty default download source.
            assertEquals(
                UiState.Joined(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN)),
                host.container.stateFlow.value,
            )
            // The title and the QR both come from the forged config — no name fetch, no scanned QR.
            assertEquals("Anna's Birthday", host.eventName.value)
            assertNotNull(host.inviteUrl.value)
        }

    @Test
    fun `in_sync forges the settled joined layer`() = runTest {
        val host = assertNotNull(forgeStatusHost("in_sync", backgroundScope))
        // completed == total and the download arm empty → both arrows hidden → InSync, reached with the
        // benign default AttestedSource (no token) and download source (no imports).
        assertEquals(UiState.Joined(SyncHealth.InSync), host.container.stateFlow.value)
        assertEquals("Anna's Birthday", host.eventName.value)
        assertNotNull(host.inviteUrl.value)
    }

    @Test
    fun `an unrecognized state is rejected so the shell falls back to the live stack`() = runTest {
        assertNull(forgeStatusHost("not-a-state", backgroundScope))
        assertNull(forgeStatusHost("", backgroundScope))
    }
}
