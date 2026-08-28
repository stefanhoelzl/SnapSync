package app.snapsync.integration

import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.Layer
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusSources
import app.snapsync.presentation.UiState
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * The version gate end to end (capability `min-app-version`), over the REAL composed core.
 *
 * Nothing here is simulated between the wire and the screen. The mini-edge answers a genuine `426`; the
 * REAL interceptor the device installs reads it; the REAL `AppVersionGate` on the composed `AppCore`
 * records it; and the REAL container reduces it into `UiState`. What each of those steps proves
 * separately is asserted separately — `CredentialInterceptorTest` for the branch, `MiniEdgeV2Test` for
 * the refusal — and what only this can prove is that they are CONNECTED, which is exactly the class of
 * defect a device meets and no unit test sees.
 *
 * The second test is the one that matters most in the field. A refusal that never clears is a member
 * stuck on an update screen after they have already updated, and nothing about the screen itself would
 * reveal it: the wiring that heals it is a single `onServed` on a status nobody thinks about.
 */
class VersionGateIntegrationTest {

    private val storeUrl = "https://apps.apple.com/de/app/id6781692480"

    @Test
    fun a_refused_build_reaches_the_update_screen_carrying_the_minimum_and_the_remedy() = worldTest {
        val w = World(this)
        w.store.minAppVersion = "0.4"
        w.appVersion = "0.3" // a build that predates v2
        val scope = CoroutineScope(coroutineContext)
        try {
            val host = statusHost(w, scope)

            // Any backend call at all — the gate precedes every route, which is the whole reason a
            // refused build cannot do anything and the screen may say so unconditionally.
            w.deviceFiles.list(w.ownDeviceId)

            val layer = host.await { it.layer is Layer.UpdateRequired }.layer
            assertIs<Layer.UpdateRequired>(layer)
            assertEquals("0.4", layer.minimumVersion, "the screen names the version the backend named")
            assertEquals(storeUrl, layer.storeUrl, "and offers the remedy, or it is a dead end")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun updating_clears_the_refusal_and_the_screen_goes_away() = worldTest {
        val w = World(this)
        w.store.minAppVersion = "0.4"
        w.appVersion = "0.3"
        val scope = CoroutineScope(coroutineContext)
        try {
            val host = statusHost(w, scope)
            w.deviceFiles.list(w.ownDeviceId)
            host.await { it.layer is Layer.UpdateRequired }

            // The member updates. Nothing else changes — no restart, no reset, no re-join.
            w.appVersion = "0.4"
            w.deviceFiles.list(w.ownDeviceId)

            val layer = host.await { it.layer !is Layer.UpdateRequired }.layer
            assertTrue(layer is Layer.CreateEvent, "back to the ordinary front door, not a stuck screen")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_served_build_never_sees_the_screen() = worldTest {
        // The negative direction, and it is not ceremony: an interceptor that reported on every response
        // would park every member on an update screen, and the two tests above would still pass.
        val w = World(this)
        w.store.minAppVersion = "0.4"
        w.appVersion = "0.9"
        val scope = CoroutineScope(coroutineContext)
        try {
            val host = statusHost(w, scope)
            w.deviceFiles.list(w.ownDeviceId)
            assertNull(w.core.versionGate.refusal.value, "a served build is not refused")
            assertTrue(host.container.stateFlow.value.layer !is Layer.UpdateRequired)
        } finally {
            scope.cancel()
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun statusHost(w: World, scope: CoroutineScope) = StatusContainerHost(
        StatusSources(
            sync = w.syncStatusSource,
            permission = w.permission.permission,
            config = w.configSource.config,
            // The two the gate needs, wired exactly as the iOS shell wires them.
            versionRefusal = w.core.versionGate.refusal,
            appStoreUrl = storeUrl,
        ),
        scope = scope,
        cutoffFormatter = fixedCutoffFormatter(),
    )

    private suspend fun StatusContainerHost.await(predicate: (UiState) -> Boolean): UiState =
        withTimeout(5_000) { container.stateFlow.first(predicate) }
}

private fun fixedCutoffFormatter() = CutoffFormatter(
    now = { Instant.parse("2026-07-09T12:00:00Z") },
    zone = TimeZone.UTC,
)
