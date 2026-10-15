package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.format.InviteLinkText
import app.snapsync.format.TimestampText
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RenameState
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val JOINED = "11111111-1111-4111-8111-111111111111"
private const val OTHER = "22222222-2222-4222-8222-222222222222"

private val CONFIG = EventConfig(
    eventId = testEventId(JOINED),
    name = "Anna's Birthday",
    minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T14:32:11Z")),
    startsAt = EventStart(TimestampText.parse("2026-07-06T14:32:11Z")),
    endsAt = EventEnd(TimestampText.parse("2026-07-13T14:32:11Z")),
    maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-13T14:32:11Z")),
    deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
)

private class IdleSync : SyncStatusSource {
    override val status: StateFlow<SyncStatus> =
        MutableStateFlow(SyncStatus.Ready(SyncProgress(0, 0, 0, 0, active = false, estimatedRemaining = null)))
}

/**
 * **Non-idempotent commands are in flight before they first suspend** (capability `sync-status`; decision
 * record `harden-seam-bug-classes`, D13): a second tap while the first is running fires nothing, a result lands only
 * on the surface that started it, and a stale terminal result never outlives its surface.
 *
 * Driven on a real container over a real scope, as `StatusContainerHostSurfacesTest` is and for its reason: the
 * `orbit-test` harness answers the seed state while the intents really run.
 */
class GuardedIntentTest {

    private class Spy {
        var leaves = 0
        val leaveStarted = CompletableDeferred<Unit>()
        val leaveMayFinish = CompletableDeferred<Unit>()
    }

    private fun onHost(
        spy: Spy,
        config: MutableStateFlow<EventConfig?>,
        rename: suspend (EventId, String) -> RenameResult? = { _, _ -> null },
        body: suspend (StatusContainerHost) -> Unit,
    ) = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val commands = TestCommands(
                leave = {
                    spy.leaves++
                    spy.leaveStarted.complete(Unit)
                    scope.launch {
                        spy.leaveMayFinish.await()
                        config.value = null
                    }
                },
                rename = rename,
            )
            try {
                body(
                    StatusContainerHost(
                        StatusSources(
                            IdleSync(),
                            MutableStateFlow(GalleryAccess.GRANTED),
                            config,
                            requests = commands.requests.flows,
                        ),
                        scope,
                        joinDetails = joinDetails {
                            found(
                                "Trip",
                                EventStart(TimestampText.parse("2026-07-06T00:00:00Z")),
                                EventEnd(TimestampText.parse("2026-07-13T00:00:00Z")),
                                DeletesAt(TimestampText.parse("2026-08-05T00:00:00Z")),
                            )
                        },
                        commands = commands,
                        cutoffFormatter = CutoffFormatter(
                            now = { Instant.parse("2026-07-09T12:00:00Z") },
                            zone = TimeZone.UTC,
                        ),
                        queries = noQueries,
                        diagnostics = testDiagnostics(),
                        reduction = Dispatchers.Default,
                    ),
                )
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun StatusContainerHost.stateWhere(what: String, predicate: (UiState) -> Boolean): UiState =
        withTimeout(5.seconds) {
            try {
                screen.first(predicate)
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("never reached: $what (last was ${screen.value})", timeout)
            }
        }

    private suspend fun StatusContainerHost.awaitSwitchConfirmation() {
        onOpenUrl(InviteLinkText.format(InviteLink(testEventId(OTHER), null)))
        stateWhere("the switch confirmation") { (it.layer as? Layer.Joined)?.pendingSwitch?.eventId == OTHER }
    }

    @Test
    fun `a double-tapped switch leaves once`() {
        val spy = Spy()
        onHost(spy, MutableStateFlow(CONFIG)) { host ->
            host.awaitSwitchConfirmation()

            host.onConfirmSwitch()
            spy.leaveStarted.await()
            host.onConfirmSwitch() // the second tap, while the first leave is still running
            spy.leaveMayFinish.complete(Unit)
            host.stateWhere("the join surface for the new event") { it.layer is Layer.JoiningEvent }

            assertEquals(1, spy.leaves)
        }
    }

    @Test
    fun `a switch cancelled while its leave runs stays cancelled`() {
        // B13: the leave used to re-derive the pending join it started from once it finished, so a member who
        // cancelled in the meantime had the join surface reappear over their cancel.
        val spy = Spy()
        onHost(spy, MutableStateFlow(CONFIG)) { host ->
            host.awaitSwitchConfirmation()

            host.onConfirmSwitch()
            spy.leaveStarted.await()
            host.onCancelSwitch().join()
            spy.leaveMayFinish.complete(Unit)

            host.stateWhere("no event, and no join surface") { it.layer is Layer.CreateEvent }
        }
    }

    @Test
    fun `a rename result that landed before the sheet reopened does not greet the next edit`() {
        // The sheet closes itself on a success. Each edit's answer is keyed by its own request, so an answer that
        // landed for an earlier edit is none of the next one's: reopening the sheet shows a fresh edit.
        val spy = Spy()
        onHost(spy, MutableStateFlow(CONFIG), rename = { _, _ -> RenameResult.Renamed }) { host ->
            host.onRenameEvent(CONFIG.eventId.value, "Lake week")
            host.stateWhere("the rename answered") { (it.layer as? Layer.Joined)?.renameState == RenameState.Succeeded }
            host.surfaces.onRenameOpen()
            val reopened = host.stateWhere("the rename sheet") { it.overlays.renaming }
            assertEquals(RenameState.Idle, assertIs<Layer.Joined>(reopened.layer).renameState)
        }
    }
}
