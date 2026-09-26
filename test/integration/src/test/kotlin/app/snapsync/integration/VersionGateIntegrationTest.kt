package app.snapsync.integration

import app.snapsync.model.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The version gate end to end (capability `app-update-required`), over the REAL composed core, driven through the
 * control protocol.
 *
 * Nothing here is simulated between the wire and the screen. The mini-edge answers a genuine `426`; the REAL
 * `HttpBackend` the device runs over carries it; the REAL authenticated backend composed in `AppCore` hands it to the
 * REAL `AppVersionGate`, which records it; and the REAL container reduces it into `UiState`. What each of those steps
 * proves separately is asserted separately — `CredentialedBackendTest` for the branch, `MiniEdgeV2Test` for the
 * refusal — and what only this can prove is
 * that they are CONNECTED, which is exactly the class of defect a device meets and no unit test sees.
 *
 * The second test is the one that matters most in the field. A refusal that never clears is a member stuck on an
 * update screen after they have already updated, and nothing about the screen itself would reveal it: the wiring
 * that heals it is a single `onServed` on a status nobody thinks about.
 */
class VersionGateIntegrationTest {

    @Test
    fun a_refused_build_reaches_the_update_screen_carrying_the_minimum_and_the_remedy() = rigTest {
        createAndJoin()
        device("backend/min-app-version", "minimum" to "0.4")
        device("app-version", "version" to "0.3") // a build that predates v2

        // Any backend call at all — the gate precedes every route, which is the whole reason a refused build cannot
        // do anything and the screen may say so unconditionally.
        anyBackendCall()

        val layer = awaitState { it.ui.layer is Layer.UpdateRequired }.ui.layer
        assertIs<Layer.UpdateRequired>(layer)
        assertEquals("0.4", layer.minimumVersion, "the screen names the version the backend named")
        assertNotNull(layer.storeUrl, "and offers the remedy, or it is a dead end")
    }

    @Test
    fun updating_clears_the_refusal_and_the_screen_goes_away() = rigTest {
        createAndJoin()
        device("backend/min-app-version", "minimum" to "0.4")
        device("app-version", "version" to "0.3")
        anyBackendCall()
        awaitState { it.ui.layer is Layer.UpdateRequired }

        // The member updates. Nothing else changes — no restart, no reset, no re-join.
        device("app-version", "version" to "0.4")
        anyBackendCall()

        val layer = awaitState { it.ui.layer !is Layer.UpdateRequired }.ui.layer
        assertIs<Layer.Joined>(layer, "back to the member's ordinary screen, not a stuck one")
    }

    @Test
    fun a_served_build_never_sees_the_screen() = rigTest {
        // The negative direction, and it is not ceremony: a gate that reported on every response would park
        // every member on an update screen, and the two tests above would still pass.
        createAndJoin()
        device("backend/min-app-version", "minimum" to "0.4")
        device("app-version", "version" to "0.9")

        anyBackendCall()

        neverWithin(what = "a served build is not refused") { it.ui.layer is Layer.UpdateRequired }
    }

    /**
     * A backend call that changes nothing the screen shows: the download reconcile reads the joined event's union.
     * The member joins BEFORE the gate is raised, so the join itself is served.
     */
    private suspend fun Rig.anyBackendCall() {
        reconcile()
    }
}
