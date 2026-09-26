package app.snapsync.presentation

import app.snapsync.feature.membership.readmodel.MutableRenameStatusSource
import app.snapsync.feature.membership.readmodel.RenameStatus
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.JoinLoad
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import app.snapsync.model.Layer
import app.snapsync.model.UiState

private const val JOINED = "11111111-1111-4111-8111-111111111111"
private const val OTHER = "22222222-2222-4222-8222-222222222222"

private val CONFIG = EventConfig(
    eventId = JOINED,
    name = "Anna's Birthday",
    minPhotoDate = captureCutoff("2026-07-06T14:32:11Z"),
    startsAt = eventStart("2026-07-06T14:32:11Z"),
    endsAt = eventEnd("2026-07-13T14:32:11Z"),
    maxPhotoDate = captureCeiling("2026-07-13T14:32:11Z"),
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
        var resets = 0
        val leaveStarted = CompletableDeferred<Unit>()
        val leaveMayFinish = CompletableDeferred<Unit>()
    }

    private fun onHost(
        spy: Spy,
        config: MutableStateFlow<EventConfig?>,
        rename: MutableRenameStatusSource = MutableRenameStatusSource(),
        body: suspend (StatusContainerHost) -> Unit,
    ) = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                body(
                    StatusContainerHost(
                        StatusSources(IdleSync(), MutableStateFlow(GalleryAccess.GRANTED), config, rename = rename),
                        scope,
                        queries = joinDetails {
                            JoinLoad.Found(
                                "Trip",
                                eventStart("2026-07-06T00:00:00Z"),
                                eventEnd("2026-07-13T00:00:00Z"),
                                deletesAt("2026-08-05T00:00:00Z"),
                            )
                        },
                        commands = testCommands(
                            leave = {
                                spy.leaves++
                                spy.leaveStarted.complete(Unit)
                                spy.leaveMayFinish.await()
                                config.value = null
                            },
                            resetRename = {
                                spy.resets++
                                rename.set(RenameStatus.Idle)
                            },
                        ),
                        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
                        diagnostics = testDiagnostics(),
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
                container.stateFlow.first(predicate)
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("never reached: $what (last was ${container.stateFlow.value})", timeout)
            }
        }

    private suspend fun StatusContainerHost.awaitSwitchConfirmation() {
        onOpenUrl(encodeEventUrl(EventLinkPayload(OTHER)))
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
    fun `a rename result that landed after the sheet was dismissed does not greet the next edit`() {
        // The sheet closes itself on a success. A result that arrived while it was dismissed stayed latched, and
        // reopening the sheet read it as the new edit's outcome and closed at once.
        val spy = Spy()
        val rename = MutableRenameStatusSource(RenameStatus.Succeeded)
        onHost(spy, MutableStateFlow(CONFIG), rename) { host ->
            host.surfaces.onRenameOpen()
            host.stateWhere("the rename sheet") { it.overlays.renaming }

            assertEquals(1, spy.resets)
            assertEquals(RenameStatus.Idle, rename.renameStatus.value)
        }
    }
}
