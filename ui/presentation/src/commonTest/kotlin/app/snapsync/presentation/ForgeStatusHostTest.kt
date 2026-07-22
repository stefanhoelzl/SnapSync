package app.snapsync.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

/** The shell binds the system clock/zone; the test binds a fixed instant (step 9: no system default). */
private fun fixedFormatter() = CutoffFormatter(
    now = { Instant.parse("2026-07-09T12:00:00Z") },
    zone = TimeZone.UTC,
)

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
        val host = assertNotNull(forgeStatusHost("create", backgroundScope, fixedFormatter()))
        // Config absent + idle creation → the create input, exactly as production reduces it.
        assertEquals(UiState.CreateEvent(error = null), host.container.stateFlow.value)
    }

    @Test
    fun `joining forges the real join confirmation gate from an interactive invite link`() = runTest {
        val host = assertNotNull(forgeStatusHost("joining", backgroundScope, fixedFormatter()))
        // The factory dispatched the invite link as an Orbit intent, which runs on Orbit's own
        // (real) dispatcher rather than the test scheduler — so AWAIT the gate instead of advancing
        // virtual time. The gate reduced ITSELF from the forged inputs: an interactive event link + a
        // Found details load, with config absent (a first join, not a switch) and permission granted
        // (so readyOrExplain picks Ready rather than the access explainer).
        val state = host.container.stateFlow.first { it is UiState.JoiningEvent }
        assertEquals(UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready(EVENT_NAME, EVENT_START, EVENT_END)), state)
    }

    @Test
    fun `in_sync forges the settled joined layer`() = runTest {
        val host = assertNotNull(forgeStatusHost("in_sync", backgroundScope, fixedFormatter()))
        // completed == total and the download arm empty → both arrows hidden → InSync, reached with the
        // benign default AttestedSource (no token) and download source (no imports).
        assertEquals(UiState.Joined(SyncHealth.InSync), host.container.stateFlow.value)
        assertEquals("Anna's Birthday", host.eventName.value)
        assertNotNull(host.inviteUrl.value)
    }

    @Test
    fun `an unrecognized state is rejected so the shell falls back to the live stack`() = runTest {
        assertNull(forgeStatusHost("not-a-state", backgroundScope, fixedFormatter()))
        assertNull(forgeStatusHost("", backgroundScope, fixedFormatter()))
    }
}
