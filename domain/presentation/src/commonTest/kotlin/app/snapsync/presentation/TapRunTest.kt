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
import app.snapsync.format.TimestampText
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.ReportOutcome
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.UserCommands
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.UiIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private val MEMBERSHIP = EventConfig(
    eventId = testEventId("66666666-6666-4666-8666-666666666666"),
    name = "Lake Weekend",
    minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T00:00:00Z")),
    startsAt = EventStart(TimestampText.parse("2026-07-06T00:00:00Z")),
    endsAt = EventEnd(TimestampText.parse("2026-07-13T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-13T00:00:00Z")),
    deletesAt = DeletesAt(TimestampText.parse("2026-08-05T00:00:00Z")),
)

/** What a tap's context carries here: which tap. */
private class Tap(val name: String) : AbstractCoroutineContextElement(Tap) {
    companion object Key : CoroutineContext.Key<Tap>
}

/**
 * A tap's work (`docs/patterns.md`, "Entry events"): every intent the table launches for it, and every settings act it
 * queues, runs in the tap's context and is counted as its work until it ends — so the tap's `done` waits for it.
 */
class TapRunTest {
    private class World {
        val config = MutableStateFlow<EventConfig?>(null)
        val seen = mutableListOf<String>()
        val joined = mutableListOf<Job>()

        /** Where each settings change is saved, and answered. */
        val saved = TestCommands(reconfigure = { SettingsResult.Saved })

        /** Which tap the report was sent for — completed as it is sent. */
        val reported = CompletableDeferred<String?>()

        fun run(name: String) = TapRun(Tap(name)) { joined += it }
    }

    /** Answers each report with which tap it was sent for, and counts the settings changes it is handed. */
    private fun commands(world: World): UserCommands = object : UserCommands by TestCommands(
        sendDiagnostics = { _, _ ->
            world.reported.complete(currentCoroutineContext()[Tap]?.name)
            ReportOutcome.SENT
        },
    ) {
        override fun reconfigure(choice: ShareChoice): RequestId {
            world.seen += "reconfigure"
            return world.saved.reconfigure(choice)
        }
    }

    private fun host(world: World, scope: CoroutineScope, reduction: CoroutineDispatcher) = StatusContainerHost(
        StatusSources(
            FixedSync(SyncStatus.Ready(SyncProgress(0, 0, 0, 0, active = false, estimatedRemaining = null))),
            MutableStateFlow(GalleryAccess.GRANTED),
            world.config,
            requests = world.saved.requests.flows,
        ),
        scope,
        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
        commands = commands(world),
        queries = noQueries,
        joinDetails = unanswered,
        diagnostics = testDiagnostics(),
        reduction = reduction,
    )

    /** The host, started — as the composition starts it at assembly, before any tap — on the test's scheduler. */
    private suspend fun TestScope.started(world: World): StatusContainerHost =
        host(world, backgroundScope, StandardTestDispatcher(testScheduler)).also {
            it.screen.first()
            runCurrent()
        }

    @Test
    fun `an intent a tap launches runs in the tap's context - and is the tap's work until it ends`() = runTest {
        val world = World()
        val host = started(world)
        host.onTap(UiIntent.SendDiagnostics("stuck", "Joined"), world.run("report"))
        assertEquals(1, world.joined.size, "the intent was not counted as the tap's work")
        assertEquals("report", world.reported.await())
        world.joined.single().join()
    }

    @Test
    fun `an intent no tap launched is no tap's work`() = runTest {
        val world = World()
        val host = started(world)
        host.onSendDiagnostics("stuck", "Joined")
        assertEquals(null, world.reported.await())
        assertTrue(world.joined.isEmpty())
    }

    @Test
    fun `a settings act a tap queues runs in its turn as the tap's work`() = runTest {
        val world = World().apply { config.value = MEMBERSHIP }
        val host = started(world)
        host.surfaces.onOpenReconfigure()
        runCurrent()
        val before = world.joined.size
        host.onTap(UiIntent.SaveToAlbum(true), world.run("album"))
        val queued = world.joined.drop(before)
        assertEquals(1, queued.size, "the queued act was not counted as the tap's work")
        runCurrent()
        assertEquals("reconfigure", world.seen.lastOrNull(), "the queued act did not run")
        assertTrue(queued.single().isCompleted, "the act ran, and its count did not end")
    }
}
