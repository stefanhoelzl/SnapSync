@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.feature.creation.readmodel.CreationFailureReason
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.Layer
import app.snapsync.model.Overlays
import app.snapsync.model.ReportOutcome
import app.snapsync.model.ScreenMessage
import app.snapsync.model.SyncStatus
import app.snapsync.model.UiIntent
import app.snapsync.model.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * The two words that clear themselves (capabilities `join-event`, `privacy-security`): a rejected link's message and a
 * sent report's outcome. Each stays a few seconds after it LAST appeared — a repeat re-arms the whole window — and a
 * rejected link's message is told over whatever the create surface was saying, which returns once it clears.
 *
 * The timers run on the container's scope, which runs on a scheduler of its own — not `runTest`'s, which skips time
 * whenever the test waits — so the 4-second windows pass exactly when [Timers.advanceBy] says, never in real time.
 */
class NoticeTimersTest {

    private class Timers {
        private val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

        fun advanceBy(millis: Long) {
            scheduler.advanceTimeBy(millis)
            scheduler.runCurrent()
        }
    }

    /** Run [body] over a container whose timers run on [Timers]. */
    private fun onTimers(body: suspend (Timers) -> Unit) = runTest {
        val timers = Timers()
        try {
            body(timers)
        } finally {
            timers.scope.cancel()
        }
    }

    private fun host(
        scope: CoroutineScope,
        creation: CreationStatus = CreationStatus.Idle,
        report: suspend () -> ReportOutcome = { ReportOutcome.SENT },
    ) = StatusContainerHost(
        StatusSources(
            FixedSync(SyncStatus.Loading),
            MutableStateFlow(GalleryAccess.GRANTED),
            MutableStateFlow<EventConfig?>(null),
            creation = MutableStateFlow(creation),
        ),
        scope,
        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
        commands = testCommands(sendDiagnostics = { _, _ -> report() }),
        queries = noQueries,
        diagnostics = testDiagnostics(),
    )

    private fun UiState.createError(): ScreenMessage? = assertIs<Layer.CreateEvent>(layer).error

    /**
     * The state once the reduction has caught up — it runs off the test's scheduler, so a read that expects NOTHING
     * to have changed waits a bounded real-time grace first.
     */
    private suspend fun StatusContainerHost.settledState(): UiState {
        withContext(Dispatchers.Default) { delay(200) }
        return container.stateFlow.value
    }

    private suspend fun Timers.openBadLink(host: StatusContainerHost) {
        host.onOpenUrl("https://example.com/not-an-event").join()
        advanceBy(0)
    }

    /** A report hands back no job to await; its word's timer is armed just after the word shows. */
    private suspend fun afterTheWordIsArmed() = withContext(Dispatchers.Default) { delay(200) }

    @Test
    fun `a rejected link's message stays four seconds after it last appeared`() = onTimers { timers ->
        val host = host(timers.scope)
        timers.openBadLink(host)
        host.container.stateFlow.first { it.createError() == ScreenMessage.INVALID_LINK }

        timers.advanceBy(3_000)
        timers.openBadLink(host)
        timers.advanceBy(2_000) // 5 s after the first, 2 s after the second

        assertEquals(ScreenMessage.INVALID_LINK, host.settledState().createError(), "cleared on the first link's clock")
        timers.advanceBy(2_001)
        host.container.stateFlow.first { it.createError() == null }
    }

    @Test
    fun `a rejected link's message is told over a failed create's — which returns once it clears`() = onTimers { timers ->
        val host = host(timers.scope, CreationStatus.Failed(CreationFailureReason.INVALID_NAME))
        assertEquals(ScreenMessage.CREATE_NAME_REFUSED, host.container.stateFlow.value.createError())

        timers.openBadLink(host)
        host.container.stateFlow.first { it.createError() == ScreenMessage.INVALID_LINK }

        timers.advanceBy(4_001)
        host.container.stateFlow.first { it.createError() == ScreenMessage.CREATE_NAME_REFUSED }
    }

    @Test
    fun `a second report's word replaces the first's and stays its own four seconds`() = onTimers { timers ->
        val outcomes = ArrayDeque(listOf(ReportOutcome.SENT, ReportOutcome.SAVED))
        val host = host(timers.scope, report = { outcomes.removeFirst() })
        host.onSendDiagnostics("photos are missing", "create")
        host.container.stateFlow.first { it.overlays.reportNotice == ReportOutcome.SENT }
        afterTheWordIsArmed()

        timers.advanceBy(3_000)
        host.onSendDiagnostics("still missing", "create")
        host.container.stateFlow.first { it.overlays.reportNotice == ReportOutcome.SAVED }
        afterTheWordIsArmed()
        timers.advanceBy(2_000)

        val notice = host.settledState().overlays.reportNotice
        assertEquals(ReportOutcome.SAVED, notice, "cleared on the first word's clock")
        timers.advanceBy(2_001)
        host.container.stateFlow.first { it.overlays.reportNotice == null }
    }

    @Test
    fun `dismissing the word when none is showing changes nothing`() = onTimers { timers ->
        val host = host(timers.scope)

        host.onIntent(UiIntent.ReportNoticeDismiss)

        assertEquals(Overlays(), host.settledState().overlays)
    }
}
