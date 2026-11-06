package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.request.MembershipReadRequest
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgress
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.query.RequestStatus
import app.snapsync.query.membership.MembershipShown
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import app.snapsync.ui.state.offersMenu
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val MEMBERSHIP = TestMembership(
    eventId = testEventId("11111111-1111-4111-8111-111111111111"),
    name = "Anna's Birthday",
    minPhotoDate = CaptureCutoff(timestamp("2026-07-06T00:00:00Z")),
    startsAt = EventStart(timestamp("2026-07-06T00:00:00Z")),
    endsAt = EventEnd(timestamp("2026-07-13T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(timestamp("2026-07-13T00:00:00Z")),
    deletesAt = DeletesAt(timestamp("2099-12-31T00:00:00Z")),
)

/**
 * A membership the app cannot read, having read nothing since it started (capability `app-experience`): said, never
 * taken for none, with the app menu in reach — and trying again is one request at a time, shown as working until it
 * is answered, after which the screen shows what was found.
 */
class MembershipUnreadableTest {

    private fun onHost(
        shown: MutableStateFlow<MembershipShown>,
        commands: TestCommands,
        body: suspend (StatusContainerHost) -> Unit,
    ) = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                body(
                    testHost(
                        testQueries(
                            MutableStateFlow(SharingProgressRead.Read(SharingProgress(0, 0, 0))),
                            MutableStateFlow(PhotoGrant.FULL),
                            shown,
                            requests = commands.requests.flows,
                            network = AlwaysOnline,
                            mobileData = AlwaysAllowed,
                        ),
                        scope,
                        commands = commands.door,
                        cutoffFormatter = CutoffFormatter(
                            now = { Instant.parse("2026-07-09T12:00:00Z") },
                            zone = TimeZone.UTC,
                        ),
                        reduction = Dispatchers.Default,
                    ),
                )
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun StatusContainerHost.stateWhere(what: String, predicate: (UiState) -> Boolean): UiState =
        withTimeout(5.seconds) { screen.first(predicate) }.also { assertTrue(predicate(it), what) }

    @Test
    fun `an unreadable membership is said, with the menu in reach — never taken for none`() =
        onHost(MutableStateFlow(MembershipShown.Unreadable), TestCommands()) { host ->
            val layer = host.screen.value.layer
            assertEquals(Layer.MembershipUnreadable(retrying = false), layer)
            assertTrue(layer.offersMenu, "the menu, and with it the report, stays in reach")
        }

    @Test
    fun `none held is the create screen`() = onHost(MutableStateFlow(MembershipShown.None), TestCommands()) { host ->
        host.stateWhere("the create screen") { it.layer is Layer.CreateEvent }
    }

    @Test
    fun `trying again works once however often it is tapped, then shows what it found`() {
        val shown = MutableStateFlow<MembershipShown>(MembershipShown.Unreadable)
        val mayAnswer = CompletableDeferred<Unit>()
        val fired = mutableListOf<MembershipReadRequest>()
        val commands = TestCommands().apply {
            readMayAnswer = mayAnswer
            handedOver = { (it as? MembershipReadRequest)?.let(fired::add) }
        }
        onHost(shown, commands) { host ->
            host.onIntent(UiIntent.RetryMembershipRead)
            host.stateWhere("working") { it.layer == Layer.MembershipUnreadable(retrying = true) }
            host.onIntent(UiIntent.RetryMembershipRead)

            shown.value = MembershipShown.Held(MEMBERSHIP.held())
            mayAnswer.complete(Unit)
            host.stateWhere("what it found") { it.layer is Layer.Joined }
            assertEquals(1, fired.size, "a second tap while one ran fired nothing")
        }
    }

    @Test
    fun `a try that still cannot read stops working and offers to try again`() {
        val commands = TestCommands()
        onHost(MutableStateFlow(MembershipShown.Unreadable), commands) { host ->
            host.onIntent(UiIntent.RetryMembershipRead)
            withTimeout(5.seconds) { commands.requests.statuses.read.status.first { it is RequestStatus.Done } }
            host.stateWhere("ready to try again") { it.layer == Layer.MembershipUnreadable(retrying = false) }
        }
    }
}
