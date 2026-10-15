@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.format.TimestampText
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.UserCommands
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    eventId = testEventId("55555555-5555-4555-8555-555555555555"),
    name = "Lake Weekend",
    minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T00:00:00Z")),
    startsAt = EventStart(TimestampText.parse("2026-07-06T00:00:00Z")),
    endsAt = EventEnd(TimestampText.parse("2026-07-13T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-13T00:00:00Z")),
    deletesAt = DeletesAt(TimestampText.parse("2026-08-05T00:00:00Z")),
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

        /** The door itself: what happens as a change is handed to it — a throw there is the intent's own. */
        var door: (ShareChoice) -> Unit = {}

        /** Where every change the door takes is saved, and answered. */
        val saved = TestCommands(reconfigure = { SettingsResult.Saved })
    }

    /** The settings' door over [world]: every change it is handed is recorded, then saved. */
    private fun commands(world: World): UserCommands = object : UserCommands by world.saved {
        override fun reconfigure(choice: ShareChoice): RequestId {
            world.reconfigured += choice.direction
            world.door(choice)
            return world.saved.reconfigure(choice)
        }
    }

    private fun host(world: World, scope: CoroutineScope) = StatusContainerHost(
        StatusSources(
            FixedSync(SETTLED),
            MutableStateFlow(GalleryAccess.GRANTED),
            world.config,
            requests = world.saved.requests.flows,
        ),
        scope,
        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
        commands = commands(world),
        queries = noQueries,
        joinDetails = unanswered,
        diagnostics = testDiagnostics(onIntentError = { world.errors += it }),
        reduction = Dispatchers.Default,
    )

    @Test
    fun `a settings change that throws is reported and the next change still applies`() = runTest {
        var first = true
        val world = World().apply {
            door = {
                if (first) {
                    first = false
                    error("the save threw")
                }
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

        val state = host.screen.first { it.layer is Layer.Joined }
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
    fun `settings left open when the event's photos are deleted give way to the status`() = runTest {
        val world = World()
        val host = host(world, backgroundScope)
        host.surfaces.onOpenReconfigure()
        runCurrent()
        host.screen.first { (it.layer as? Layer.Joined)?.surface is JoinedSurface.Reconfigure }

        world.config.value = MEMBERSHIP.copy(deleted = true)

        host.screen.first {
            (it.layer as? Layer.Joined)?.let { joined -> joined.deleted && joined.surface == JoinedSurface.Status } == true
        }
    }
}
