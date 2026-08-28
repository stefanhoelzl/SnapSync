package app.snapsync.presentation

import app.snapsync.feature.status.SyncStatusSource
import app.snapsync.feature.version.AppVersionGate
import app.snapsync.model.EventConfig
import app.snapsync.model.PermissionStatus
import app.snapsync.model.SyncStatus
import app.snapsync.model.UserCommands
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.orbitmvi.orbit.test.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The version gate on the SCREEN (capability `min-app-version`): a backend refusal of this build
 * reduced into `UiState`, and the one command it offers.
 *
 * Its own file rather than more cases on `StatusContainerHostTest`, which is at its size ceiling
 * (`complexity-budgets`) — and the split is by capability rather than to make a number pass: these
 * exercise one read-model, one layer and one command, and share none of that class's join/create
 * fixtures.
 *
 * What each layer proves is asserted where it lives — `CredentialInterceptorTest` for the `426` branch,
 * `VersionGateIntegrationTest` for the whole path over the real composed core. These are the reduction:
 * which layer wins, what it carries, and that it goes away again.
 */
class VersionGateHostTest {

    @Test
    fun `a refused build reaches the update screen carrying the minimum and the remedy`() = runTest {
        val refusal = MutableStateFlow<AppVersionGate.Refusal?>(null)
        gateHost(backgroundScope, refusal).test(this) {
            runOnCreate() // the initial state is the ordinary create layer

            refusal.value = AppVersionGate.Refusal("0.4")

            val layer = awaitState().layer
            assertIs<Layer.UpdateRequired>(layer)
            assertEquals("0.4", layer.minimumVersion)
            assertEquals(STORE_URL, layer.storeUrl, "a screen whose only remedy is a link must carry it")
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `the update screen outranks a joined membership and clears again`() = runTest {
        // The rung that matters. A refused build makes no successful backend call, so a joined layer
        // would render a healthy-looking event that is doing nothing at all — the exact appearance this
        // state exists to replace. And it must CLEAR: a refusal that outlived the update would strand
        // the member on an update screen after they had already updated.
        val refusal = MutableStateFlow<AppVersionGate.Refusal?>(null)
        gateHost(
            backgroundScope, refusal,
            config = EventConfig(GATE_EVENT_ID, "Anna's Birthday", GATE_CUTOFF, maxPhotoDate = GATE_CEILING),
        ).test(this) {
            runOnCreate() // the initial state is the joined layer

            refusal.value = AppVersionGate.Refusal("0.4")
            assertIs<Layer.UpdateRequired>(awaitState().layer)

            refusal.value = null
            assertIs<Layer.Joined>(awaitState().layer)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a refusal that named no version still shows the screen and names nothing`() = runTest {
        val refusal = MutableStateFlow<AppVersionGate.Refusal?>(null)
        gateHost(backgroundScope, refusal).test(this) {
            runOnCreate()
            refusal.value = AppVersionGate.Refusal(null)
            val layer = awaitState().layer
            assertIs<Layer.UpdateRequired>(layer)
            assertNull(layer.minimumVersion, "no version was sent, so none is claimed")
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `the store tap opens the url the state carries`() = runTest {
        val opened = mutableListOf<String>()
        val host = gateHost(
            backgroundScope,
            MutableStateFlow(AppVersionGate.Refusal("0.4")),
            openLink = { opened += it },
        )
        assertIs<Layer.UpdateRequired>(host.container.stateFlow.value.layer)

        host.onOpenAppStore().join()

        assertEquals(listOf(STORE_URL), opened)
    }

    @Test
    fun `the store tap does nothing when this build carries no url`() = runTest {
        // The screen renders no button in this state, so the command should be unreachable — but the
        // container reads the URL off the state rather than trusting the caller, which is what makes
        // that true rather than merely intended.
        val opened = mutableListOf<String>()
        val host = gateHost(
            backgroundScope,
            MutableStateFlow(AppVersionGate.Refusal("0.4")),
            appStoreUrl = null,
            openLink = { opened += it },
        )
        assertIs<Layer.UpdateRequired>(host.container.stateFlow.value.layer)

        host.onOpenAppStore().join()

        assertTrue(opened.isEmpty(), "no url means no hand-off, never a composed guess")
    }
}

/** The store link a build carries, offered on the update-required screen. */
private const val STORE_URL = "https://apps.apple.com/de/app/id6781692480"

private const val GATE_EVENT_ID = "11111111-1111-4111-8111-111111111111"
private val GATE_CUTOFF = captureCutoff("2026-07-06T14:32:11Z")
private val GATE_CEILING = captureCeiling("2026-07-13T14:32:11Z")

/**
 * A container carrying only what this capability needs: the refusal read-model, the build's store URL,
 * and the one command the screen fires. Everything else takes its default, which is what makes these
 * tests state that nothing ELSE is involved in reaching the update screen.
 */
private fun gateHost(
    scope: CoroutineScope,
    refusal: MutableStateFlow<AppVersionGate.Refusal?> = MutableStateFlow(null),
    appStoreUrl: String? = STORE_URL,
    config: EventConfig? = null,
    openLink: (String) -> Unit = {},
) = StatusContainerHost(
    StatusSources(
        sync = object : SyncStatusSource {
            override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Loading)
        },
        permission = MutableStateFlow(PermissionStatus.GRANTED),
        config = MutableStateFlow(config),
        versionRefusal = refusal,
        appStoreUrl = appStoreUrl,
    ),
    scope,
    commands = UserCommands(openLink = openLink),
    cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
)
