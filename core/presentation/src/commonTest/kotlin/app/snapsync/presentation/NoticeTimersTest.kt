@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.report.ReportOutcome
import app.snapsync.domain.request.LinkOutcome
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.presentation.Switches.mobileData
import app.snapsync.presentation.UiIntents.onIntent
import app.snapsync.query.RequestStatus
import app.snapsync.query.membership.MembershipShown
import app.snapsync.ui.state.AppLink
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.LinkNotice
import app.snapsync.ui.state.Overlays
import app.snapsync.ui.state.ReportNotice
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
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
 * The words that clear themselves (capabilities `join-event`, `privacy-security`, `app-experience`): a rejected link's
 * message, a sent report's outcome, and a page the phone did not open. Each stays a few seconds after it LAST appeared —
 * a repeat re-arms the whole window — and a rejected link's message is told over whatever the create surface was
 * saying, which returns once it clears. A report's word and a page's share one place, the newer replacing the older.
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
        creation: RequestStatus<CreateResult> = Creating.Idle,
        report: suspend () -> ReportOutcome = { ReportOutcome.SENT },
        link: LinkOutcome = LinkOutcome.OPENED,
    ) = TestCommands(sendDiagnostics = { _, _ -> report() }).also { it.openLinkAnswer = { link } }.let { commands ->
        testHost(
            testQueries(
                FixedSync(SharingProgressRead.NotYetRead),
                MutableStateFlow(PhotoGrant.FULL),
                MutableStateFlow(MembershipShown.None),
                creation = MutableStateFlow(creation),
                requests = commands.requests.flows,
                network = AlwaysOnline,
                mobileData = AlwaysAllowed,
            ),
            scope,
            cutoffFormatter = CutoffFormatter(
                now = instants({ Instant.parse("2026-07-09T12:00:00Z") }),
                zone = TimeZone.UTC,
            ),
            commands = commands.door,
            shareableCount = noQueries,
            joinDetails = unanswered,
            reduction = Dispatchers.Default,
        )
    }

    private fun UiState.createError(): ScreenMessage? = assertIs<Layer.CreateEvent>(layer).passing

    private fun UiState.createFailure(): ScreenMessage? = assertIs<Layer.CreateEvent>(layer).error

    /**
     * The state once the reduction has caught up — it runs off the test's scheduler, so a read that expects NOTHING
     * to have changed waits a bounded real-time grace first.
     */
    private suspend fun StatusContainerHost.settledState(): UiState {
        withContext(Dispatchers.Default) { delay(200) }
        return screen.value
    }

    private suspend fun Timers.openBadLink(host: StatusContainerHost) {
        host.onInvite(InviteLinkVerdict.NotAnInvite).join()
        advanceBy(0)
    }

    /** A report hands back no job to await; its word's timer is armed just after the word shows. */
    private suspend fun afterTheWordIsArmed() = withContext(Dispatchers.Default) { delay(200) }

    @Test
    fun `a rejected link's message stays four seconds after it last appeared`() = onTimers { timers ->
        val host = host(timers.scope)
        timers.openBadLink(host)
        host.screen.first { it.createError() == ScreenMessage.INVALID_LINK }

        timers.advanceBy(3_000)
        timers.openBadLink(host)
        timers.advanceBy(2_000) // 5 s after the first, 2 s after the second

        assertEquals(ScreenMessage.INVALID_LINK, host.settledState().createError(), "cleared on the first link's clock")
        timers.advanceBy(2_001)
        host.screen.first { it.createError() == null }
    }

    @Test
    fun `a rejected link's message passes over a failed create's, which stays`() = onTimers { timers ->
        val host = host(timers.scope, Creating.answered(CreateResult.InvalidName))
        assertEquals(ScreenMessage.CREATE_NAME_REFUSED, host.screen.value.createFailure())

        timers.openBadLink(host)
        val passing = host.screen.first { it.createError() == ScreenMessage.INVALID_LINK }
        assertEquals(ScreenMessage.CREATE_NAME_REFUSED, passing.createFailure(), "the failure stays beneath it")

        timers.advanceBy(4_001)
        val passed = host.screen.first { it.createError() == null }
        assertEquals(ScreenMessage.CREATE_NAME_REFUSED, passed.createFailure())
    }

    @Test
    fun `a second report's word replaces the first's and stays its own four seconds`() = onTimers { timers ->
        val outcomes = ArrayDeque(listOf(ReportOutcome.SENT, ReportOutcome.SAVED))
        val host = host(timers.scope, report = { outcomes.removeFirst() })
        host.onSendDiagnostics("photos are missing", "create")
        host.screen.first { it.overlays.reportNotice == ReportNotice.SENT }
        afterTheWordIsArmed()

        timers.advanceBy(3_000)
        host.onSendDiagnostics("still missing", "create")
        host.screen.first { it.overlays.reportNotice == ReportNotice.SAVED }
        afterTheWordIsArmed()
        timers.advanceBy(2_000)

        val notice = host.settledState().overlays.reportNotice
        assertEquals(ReportNotice.SAVED, notice, "cleared on the first word's clock")
        timers.advanceBy(2_001)
        host.screen.first { it.overlays.reportNotice == null }
    }

    @Test
    fun `a page that did not open is told with where it is, and clears itself`() = onTimers { timers ->
        val host = host(timers.scope, link = LinkOutcome.NOT_OPENED)
        host.onIntent(UiIntent.OpenLink(AppLink.PRIVACY_POLICY))
        host.screen.first { it.overlays.linkNotice == LinkNotice.Site(TEST_SITE_ADDRESS) }
        afterTheWordIsArmed()

        timers.advanceBy(3_000)
        assertEquals(LinkNotice.Site(TEST_SITE_ADDRESS), host.settledState().overlays.linkNotice, "still being read")
        timers.advanceBy(1_001)
        host.screen.first { it.overlays.linkNotice == null }
    }

    @Test
    fun `a page that did not open is tapped away`() = onTimers { timers ->
        val host = host(timers.scope, link = LinkOutcome.NOT_OPENED)
        host.onIntent(UiIntent.OpenLink(AppLink.WEBSITE))
        host.screen.first { it.overlays.linkNotice != null }

        host.onIntent(UiIntent.LinkNoticeDismiss)

        host.screen.first { it.overlays.linkNotice == null }
    }

    @Test
    fun `a page that opened says nothing`() = onTimers { timers ->
        val host = host(timers.scope, link = LinkOutcome.OPENED)
        host.onIntent(UiIntent.OpenLink(AppLink.WEBSITE))

        assertEquals(Overlays(), host.settledState().overlays)
    }

    @Test
    fun `a page's word replaces a report's, and stays its own four seconds`() = onTimers { timers ->
        val host = host(timers.scope, link = LinkOutcome.NOT_OPENED)
        host.onSendDiagnostics("photos are missing", "create")
        host.screen.first { it.overlays.reportNotice == ReportNotice.SENT }
        afterTheWordIsArmed()

        timers.advanceBy(3_000)
        host.onIntent(UiIntent.OpenLink(AppLink.WEBSITE))
        val replaced = host.screen.first { it.overlays.linkNotice != null }
        assertEquals(null, replaced.overlays.reportNotice, "one word at a time")
        afterTheWordIsArmed()
        timers.advanceBy(2_000)

        assertEquals(LinkNotice.Site(TEST_SITE_ADDRESS), host.settledState().overlays.linkNotice)
        timers.advanceBy(2_001)
        host.screen.first { it.overlays.linkNotice == null }
    }

    @Test
    fun `dismissing the word when none is showing changes nothing`() = onTimers { timers ->
        val host = host(timers.scope)

        host.onIntent(UiIntent.ReportNoticeDismiss)

        assertEquals(Overlays(), host.settledState().overlays)
    }
}
