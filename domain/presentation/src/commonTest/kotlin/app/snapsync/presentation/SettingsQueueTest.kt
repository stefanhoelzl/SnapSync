@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.JoinedSurface
import app.snapsync.model.Layer
import app.snapsync.model.ReconfigureOutcome
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.UiIntent
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

private val MEMBERSHIP = EventConfig(
    eventId = "55555555-5555-4555-8555-555555555555",
    name = "Lake Weekend",
    minPhotoDate = captureCutoff("2026-07-06T00:00:00Z"),
    startsAt = eventStart("2026-07-06T00:00:00Z"),
    endsAt = eventEnd("2026-07-13T00:00:00Z"),
    maxPhotoDate = captureCeiling("2026-07-13T00:00:00Z"),
    deletesAt = deletesAt("2026-08-05T00:00:00Z"),
)

private val SETTLED = SyncStatus.Ready(SyncProgress(0, 0, 0, 0, active = false, estimatedRemaining = null))

/**
 * The settings' one queue (capability `manage-membership`): every settings act runs after the ones before it, on the
 * container's own scope. These pin what the queue does with an act that throws, and with an act whose membership —
 * or whose question — is gone by the time its turn comes. `StatusContainerHostSurfacesTest` owns what the settings
 * apply.
 *
 * The queue's worker runs on the container's scope, here the test's, so each act runs exactly at [runCurrent].
 */
class SettingsQueueTest {

    private class World {
        val config = MutableStateFlow<EventConfig?>(MEMBERSHIP)
        val reconfigured = mutableListOf<Direction>()
        val errors = mutableListOf<Throwable>()
        var reconfigure: suspend (Direction) -> ReconfigureOutcome = { ReconfigureOutcome.Saved }
    }

    private fun host(world: World, scope: CoroutineScope) = StatusContainerHost(
        StatusSources(FixedSync(SETTLED), MutableStateFlow(GalleryAccess.GRANTED), world.config),
        scope,
        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
        commands = testCommands(
            reconfigure = { _, direction, _, _, _ ->
                world.reconfigured += direction
                world.reconfigure(direction)
            },
        ),
        queries = noQueries,
        diagnostics = testDiagnostics(onIntentError = { world.errors += it }),
    )

    @Test
    fun `a settings change that throws is reported and the next change still applies`() = runTest {
        var first = true
        val world = World().apply {
            reconfigure = {
                if (first) {
                    first = false
                    error("the save threw")
                }
                ReconfigureOutcome.Saved
            }
        }
        val host = host(world, backgroundScope)
        host.surfaces.onOpenReconfigure()
        runCurrent()

        host.form.onReceiveOn(false)
        runCurrent()
        assertIs<IllegalStateException>(world.errors.single())

        host.form.onSaveToAlbum(true)
        runCurrent()

        assertEquals(2, world.reconfigured.size, "the queue stopped after a throwing act")
    }

    @Test
    fun `the settings opened after the membership ended open nothing`() = runTest {
        val world = World().apply { config.value = null }
        val host = host(world, backgroundScope)

        host.surfaces.onOpenReconfigure()
        runCurrent()
        world.config.value = MEMBERSHIP

        val state = host.container.stateFlow.first { it.layer is Layer.Joined }
        val surface = (state.layer as Layer.Joined).surface
        assertEquals(JoinedSurface.Status, surface, "a later membership opened on its settings")
        assertTrue(world.errors.isEmpty(), "${world.errors}")
    }

    @Test
    fun `a change whose membership ended before its turn applies nothing`() = runTest {
        val world = World()
        val host = host(world, backgroundScope)
        host.surfaces.onOpenReconfigure()
        runCurrent()

        host.form.onSaveToAlbum(true)
        world.config.value = null
        runCurrent()

        assertTrue(world.reconfigured.isEmpty())
        assertTrue(world.errors.isEmpty(), "${world.errors}")
    }

    @Test
    fun `stop sharing tapped after keep sharing applies nothing`() = runTest {
        val world = World()
        val host = host(world, backgroundScope)
        host.surfaces.onOpenReconfigure()
        runCurrent()
        host.form.onShareOn(false)
        runCurrent()

        host.onIntent(UiIntent.KeepSharing)
        host.onIntent(UiIntent.ConfirmStopSharing)
        runCurrent()

        assertTrue(world.reconfigured.isEmpty(), "a dropped withdrawal applied")
    }

    @Test
    fun `stop sharing after the membership ended applies nothing`() = runTest {
        val world = World()
        val host = host(world, backgroundScope)
        host.surfaces.onOpenReconfigure()
        runCurrent()
        host.form.onShareOn(false)
        runCurrent()

        world.config.value = null
        host.settings.onConfirmStopSharing()
        runCurrent()

        assertTrue(world.reconfigured.isEmpty())
        assertTrue(world.errors.isEmpty(), "${world.errors}")
    }

    @Test
    fun `settings left open when the event closes give way to the status`() = runTest {
        val world = World()
        val host = host(world, backgroundScope)
        host.surfaces.onOpenReconfigure()
        runCurrent()
        host.container.stateFlow.first { (it.layer as? Layer.Joined)?.surface is JoinedSurface.Reconfigure }

        world.config.value = MEMBERSHIP.copy(closed = true)

        host.container.stateFlow.first {
            (it.layer as? Layer.Joined)?.let { joined -> joined.closed && joined.surface == JoinedSurface.Status } == true
        }
    }
}
