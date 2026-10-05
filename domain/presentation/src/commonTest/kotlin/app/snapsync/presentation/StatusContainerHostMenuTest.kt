package app.snapsync.presentation

import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.AppLink
import app.snapsync.model.BuildLabel
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.JoinedSurface
import app.snapsync.model.Layer
import app.snapsync.model.Overlays
import app.snapsync.model.ReportOutcome
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.UiIntent
import app.snapsync.model.UiState
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val MEMBERSHIP = EventConfig(
    eventId = "11111111-1111-4111-8111-111111111111",
    name = "Anna's Birthday",
    minPhotoDate = captureCutoff("2026-07-06T14:32:11Z"),
    startsAt = eventStart("2026-07-06T14:32:11Z"),
    endsAt = eventEnd("2026-07-13T14:32:11Z"),
    maxPhotoDate = captureCeiling("2026-07-13T14:32:11Z"),
)

private class MenuTestSync : SyncStatusSource {
    override val status: StateFlow<SyncStatus> =
        MutableStateFlow(SyncStatus.Ready(SyncProgress(0, 0, 0, 0, active = false, estimatedRemaining = null)))
}

/**
 * The app menu and the word on a sent report (capabilities `sync-status`, `privacy-security`): what opening, closing
 * and following the menu reduce to, where the menu is withheld, and what the user is told once a report is handed off.
 *
 * Like the surfaces test, these read the `stateFlow` the screen collects and await a predicate on it, on real
 * dispatchers — so the notice's clear is a real few-second wait, bounded generously.
 */
class StatusContainerHostMenuTest {

    private class World {
        val config = MutableStateFlow<EventConfig?>(MEMBERSHIP)
        val creation = MutableStateFlow<CreationStatus>(CreationStatus.Idle)
        val links = mutableListOf<String>()
        val reports = mutableListOf<String>()
        var outcome: suspend () -> ReportOutcome = { ReportOutcome.SENT }
    }

    private fun onHost(world: World = World(), body: suspend (StatusContainerHost, World) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val host = StatusContainerHost(
                    StatusSources(MenuTestSync(), MutableStateFlow(GalleryAccess.GRANTED), world.config, creation = world.creation),
                    scope,
                    commands = testCommands(
                        openLink = { world.links += it },
                        sendDiagnostics = { note, _ -> world.reports += note; world.outcome() },
                    ),
                    cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
                    queries = noQueries,
                    diagnostics = testDiagnostics(),
                    build = BuildLabel("0.12", "2140"),
                )
                body(host, world)
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun StatusContainerHost.stateWhere(what: String, predicate: (UiState) -> Boolean): UiState =
        withTimeout(10.seconds) { container.stateFlow.first(predicate) }
            .also { assertTrue(predicate(it), what) }

    @Test
    fun `every state carries the build the menu names`() = onHost { host, _ ->
        host.stateWhere("the build label") { it.build == BuildLabel("0.12", "2140") }
    }

    @Test
    fun `report a problem from the menu replaces the menu with the sheet — and dismissing leaves nothing open`() =
        onHost { host, _ ->
            host.onIntent(UiIntent.MenuOpen)
            host.stateWhere("menu open") { it.overlays.menuOpen }
            host.onIntent(UiIntent.MenuReportBug)
            val sheet = host.stateWhere("sheet open") { it.overlays.reportingBug }
            assertFalse(sheet.overlays.menuOpen, "the sheet never stacks on the menu")
            host.onIntent(UiIntent.ReportBugDismiss)
            host.stateWhere("nothing open") { it.overlays == Overlays() }
        }

    @Test
    fun `a link closes the menu and opens the page outside the app`() = onHost { host, world ->
        host.onIntent(UiIntent.MenuOpen)
        host.stateWhere("menu open") { it.overlays.menuOpen }
        host.onIntent(UiIntent.OpenLink(AppLink.PRIVACY_POLICY))
        host.stateWhere("menu closed") { !it.overlays.menuOpen }
        withTimeout(5.seconds) { while (world.links.isEmpty()) delay(10) }
        assertEquals(listOf(AppLink.PRIVACY_POLICY.url), world.links)
    }

    @Test
    fun `a create in flight withholds the menu and closes an open one`() {
        val world = World().apply { config.value = null }
        onHost(world) { host, _ ->
            host.onIntent(UiIntent.MenuOpen)
            host.stateWhere("menu open on the create layer") { it.layer is Layer.CreateEvent && it.overlays.menuOpen }
            world.creation.value = CreationStatus.InFlight
            host.stateWhere("create in flight, menu masked") { it.layer == Layer.CreatingEvent && !it.overlays.menuOpen }
        }
    }

    @Test
    fun `the settings surface withholds the menu`() = onHost { host, _ ->
        host.onIntent(UiIntent.MenuOpen)
        host.stateWhere("menu open while joined") { it.layer is Layer.Joined && it.overlays.menuOpen }
        host.onIntent(UiIntent.OpenReconfigure)
        host.stateWhere("settings open, menu masked") {
            (it.layer as? Layer.Joined)?.surface is JoinedSurface.Reconfigure && !it.overlays.menuOpen
        }
    }

    @Test
    fun `each report outcome is told — and the word clears itself`() {
        for (outcome in ReportOutcome.entries) {
            val world = World().apply { this.outcome = { outcome } }
            onHost(world) { host, _ ->
                host.onSendDiagnostics("photos are missing", "joined")
                host.stateWhere("told $outcome") { it.overlays.reportNotice == outcome }
                host.stateWhere("cleared") { it.overlays.reportNotice == null }
            }
        }
    }

    @Test
    fun `a command that fails outright is told as not sent`() {
        val world = World().apply { outcome = { error("collecting the dump failed") } }
        onHost(world) { host, _ ->
            host.onSendDiagnostics("photos are missing", "joined")
            host.stateWhere("not sent") { it.overlays.reportNotice == ReportOutcome.NOT_SENT }
        }
    }

    @Test
    fun `tapping the word dismisses it`() = onHost { host, _ ->
        host.onSendDiagnostics("photos are missing", "joined")
        host.stateWhere("told") { it.overlays.reportNotice == ReportOutcome.SENT }
        host.onIntent(UiIntent.ReportNoticeDismiss)
        host.stateWhere("dismissed") { it.overlays.reportNotice == null }
    }

    @Test
    fun `cancelling the sheet tells nothing`() = onHost { host, world ->
        host.onIntent(UiIntent.ReportBugOpen)
        host.stateWhere("sheet open") { it.overlays.reportingBug }
        host.onIntent(UiIntent.ReportBugDismiss)
        host.stateWhere("sheet closed") { !it.overlays.reportingBug }
        delay(200)
        assertEquals(null, host.container.stateFlow.value.overlays.reportNotice)
        assertTrue(world.reports.isEmpty())
    }
}
