package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.format.InviteLinkText
import app.snapsync.format.TimestampText
import app.snapsync.model.AlbumContents
import app.snapsync.model.DiagnosticKeys
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.ReportContext
import app.snapsync.model.ReportDestination
import app.snapsync.model.ReportOutcome
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.UserQueries
import app.snapsync.query.membership.JoinDetailsQuery
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.AlbumHolds
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.Overlays
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.RangeView
import app.snapsync.ui.state.RenameState
import app.snapsync.ui.state.ReportTarget
import app.snapsync.ui.state.ShareCount
import app.snapsync.ui.state.UiState
import app.snapsync.ui.state.step
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val EVENT_ID = "11111111-1111-4111-8111-111111111111"

/** A membership whose cutoff sits exactly on the floor and ceiling, so the form seeds to both presets. */
private val CONFIG = EventConfig(
    eventId = testEventId(EVENT_ID),
    name = "Anna's Birthday",
    minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T14:32:11Z")),
    startsAt = EventStart(TimestampText.parse("2026-07-06T14:32:11Z")),
    endsAt = EventEnd(TimestampText.parse("2026-07-13T14:32:11Z")),
    maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-13T14:32:11Z")),
    deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
)

private class FakeSync : SyncStatusSource {
    override val status: StateFlow<SyncStatus> =
        MutableStateFlow(SyncStatus.Ready(SyncProgress(0, 0, 0, 0, active = false, estimatedRemaining = null)))
}

private data class Reconfigure(
    val eventId: String,
    val direction: Direction,
    val from: CaptureCutoff,
    val until: CaptureCeiling,
    val saveToAlbum: Boolean,
)

private class Spy {
    val renames = mutableListOf<Pair<String, String>>()
    var renameAnswer: RenameResult? = null
    val reconfigures = mutableListOf<Reconfigure>()
    var reconfigureOutcome = SettingsResult.Saved

    /** Whether a landed save is seen in the membership read at once — false plays a read that lags the write. */
    var configFollows = true
    val diagnostics = mutableListOf<Pair<String, ReportContext>>()
}

/**
 * The **overlay and settings surfaces** of the status container — the rename dialog, the leave
 * confirmation, the diagnostic sheet, and the reconfigure form (capabilities `manage-membership`,
 * `manage-membership`, `privacy-security`, `manage-membership`).
 *
 * `StatusContainerHostTest` covers the join gate, permissions, direction and sync health exhaustively;
 * this whole family was reached by nothing. Each command here "reduces and nothing more" — which is
 * exactly why an untested one fails invisibly: a navigation act that quietly touched a port, or a
 * pre-fill that tracked a background refresh instead of freezing, changes no assertion anywhere else.
 *
 * ⚠️ Driven on a REAL container over a real scope rather than through `orbit-test`'s `test()` harness,
 * which the neighbouring file uses. The harness substitutes its own container, so `screen`
 * keeps answering the seed state while the intents really do run — assertions on a spy pass and
 * assertions on state silently do not. These tests read the same `stateFlow` the screen collects, and
 * await a predicate on it rather than a fixed emission count, so an extra reduction upstream does not
 * make them flaky.
 */
class StatusContainerHostSurfacesTest {

    private fun host(
        scope: CoroutineScope,
        spy: Spy = Spy(),
        config: MutableStateFlow<EventConfig?> = MutableStateFlow(CONFIG),
        sendDiagnostics: suspend (String, ReportContext) -> ReportOutcome = { _, _ -> ReportOutcome.SENT },
        queries: UserQueries = noQueries,
        joinDetails: JoinDetailsQuery = unanswered,
        onCommitJoin: suspend (eventId: String) -> Unit = {},
        reportDestination: ReportDestination = ReportDestination.DEVELOPER,
        albumContents: AlbumContents = AlbumContents.SHARED_AND_RECEIVED,
    ): StatusContainerHost {
        val commands = TestCommands(
            reconfigure = { choice ->
                spy.reconfigures += Reconfigure(
                    choice.event.value, choice.direction, choice.from, choice.until, choice.saveToAlbum,
                )
                // A save that lands rewrites the membership, as the use-case does (its clamp is not under test here).
                if (spy.reconfigureOutcome == SettingsResult.Saved && spy.configFollows) {
                    config.value = config.value?.copy(
                        direction = choice.direction,
                        minPhotoDate = choice.from,
                        maxPhotoDate = choice.until,
                        saveToAlbum = choice.saveToAlbum,
                    )
                }
                spy.reconfigureOutcome
            },
            rename = { id, name ->
                spy.renames += id.value to name
                spy.renameAnswer
            },
            sendDiagnostics = sendDiagnostics,
            commitJoin = { join ->
                onCommitJoin(join.event.id.value)
                JoinCommit.Committed
            },
        )
        return host(scope, config, commands, queries, joinDetails, reportDestination, albumContents)
    }

    private fun host(
        scope: CoroutineScope,
        config: MutableStateFlow<EventConfig?>,
        commands: TestCommands,
        queries: UserQueries,
        joinDetails: JoinDetailsQuery,
        reportDestination: ReportDestination,
        albumContents: AlbumContents,
    ) = StatusContainerHost(
        StatusSources(FakeSync(), MutableStateFlow(GalleryAccess.GRANTED), config, requests = commands.requests.flows),
        scope,
        commands = commands,
        cutoffFormatter = CutoffFormatter(
            now = { Instant.parse("2026-07-09T12:00:00Z") },
            zone = TimeZone.UTC,
        ),
        queries = queries,
        joinDetails = joinDetails,
        diagnostics = testDiagnostics(),
        reportDestination = reportDestination,
        albumContents = albumContents,
        reduction = Dispatchers.Default,
    )

    /** Await the first state satisfying [predicate] — failing loudly rather than hanging if none comes. */
    private suspend fun StatusContainerHost.stateWhere(
        what: String,
        predicate: (UiState) -> Boolean,
    ): UiState = withTimeout(5.seconds) {
        try {
            screen.first(predicate)
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("never reached: $what (last was ${screen.value})", timeout)
        }
    }

    private suspend fun StatusContainerHost.reconfigureForm(): RangeView {
        val state = stateWhere("the reconfigure surface") {
            (it.layer as? Layer.Joined)?.surface is JoinedSurface.Reconfigure
        }
        val joined = assertIs<Layer.Joined>(state.layer)
        return assertIs<JoinedSurface.Reconfigure>(joined.surface).range
    }

    private fun onHost(
        spy: Spy = Spy(),
        config: MutableStateFlow<EventConfig?> = MutableStateFlow(CONFIG),
        sendDiagnostics: suspend (String, ReportContext) -> ReportOutcome = { _, _ -> ReportOutcome.SENT },
        queries: UserQueries = noQueries,
        joinDetails: JoinDetailsQuery = unanswered,
        onCommitJoin: suspend (eventId: String) -> Unit = {},
        reportDestination: ReportDestination = ReportDestination.DEVELOPER,
        albumContents: AlbumContents = AlbumContents.SHARED_AND_RECEIVED,
        body: suspend (StatusContainerHost) -> Unit,
    ) = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                body(
                    host(scope, spy, config, sendDiagnostics, queries, joinDetails, onCommitJoin, reportDestination, albumContents),
                )
            } finally {
                scope.cancel()
            }
        }
    }

    // ---- membership-scoped surface state (capability `sync-status`) ---------------------------------

    private fun UiState.onSettings() = (layer as? Layer.Joined)?.surface is JoinedSurface.Reconfigure

    @Test
    fun `settings open when the membership changes do not follow into the next one`() {
        // B7: the flag survived a switch, so the new membership opened on the settings surface — pre-filled from
        // nothing the member had chosen for it.
        val config = MutableStateFlow<EventConfig?>(CONFIG)
        return onHost(config = config) { host ->
            host.surfaces.onOpenReconfigure()
            host.stateWhere("the settings surface") { it.onSettings() }

            config.value = CONFIG.copy(eventId = testEventId("22222222-2222-4222-8222-222222222222"), name = "Trip")
            val next = host.stateWhere(
                "the next membership",
            ) { (it.layer as? Layer.Joined)?.membership?.name == "Trip" }
            assertTrue(!next.onSettings(), "the new membership opens on the status screen")
        }
    }

    @Test
    fun `settings do not survive a leave and a rejoin of the same event`() {
        // The rejoin goes through the join gate, as a member's does: that is where a membership begins, and where
        // its surface state is reset. The config change alone could not be relied on to say so — the leave and
        // the rejoin of one event can conflate in the StateFlow into A → A.
        val config = MutableStateFlow<EventConfig?>(CONFIG)
        val details = joinDetails {
            found(
                CONFIG.name,
                CONFIG.startsAt,
                CONFIG.endsAt,
                DeletesAt(TimestampText.parse("2026-08-05T14:32:11Z")),
            )
        }
        return onHost(config = config, joinDetails = details, onCommitJoin = { config.value = CONFIG }) { host ->
            host.surfaces.onOpenReconfigure()
            host.stateWhere("the settings surface") { it.onSettings() }

            config.value = null
            host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null)))
            host.stateWhere("the join surface, ready") {
                ((it.layer as? Layer.JoiningEvent)?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready
            }
            host.onConfirmJoin()
            val rejoined = host.stateWhere("the rejoined membership") { it.layer is Layer.Joined }
            assertTrue(!rejoined.onSettings(), "a fresh membership of the same event opens on the status screen")
        }
    }

    // ---- the shareable count (capability `join-event`) ------------------------------------------

    private fun UiState.reconfigureCount(): ShareCount? =
        ((layer as? Layer.Joined)?.surface as? JoinedSurface.Reconfigure)?.range?.shareCount

    @Test
    fun `the container counts the range the surface resolves and recounts when it changes`() {
        // The count is reduced state now: the container asks the query bundle, keyed by the resolved range,
        // and the screen renders what comes back. The whole event reaches back to 5 photos; From now shares 1.
        val eventStart = CONFIG.startsAt.at
        val asked = mutableListOf<CaptureCutoff>()
        return onHost(
            queries = counting { from, _ ->
                asked += from
                if (from.at == eventStart) 5 else 1
            },
        ) { host ->
            host.surfaces.onOpenReconfigure()
            host.stateWhere("the event-start count") { it.reconfigureCount() == ShareCount.Ready(5) }

            // From now starts later than the whole event: it is asked about, and counted once it applies.
            host.form.onRangePreset(RangeChoice.FROM_NOW)
            host.stateWhere("the question") { it.settings()?.askingToStopSharing == true }
            host.settings.onConfirmStopSharing()
            host.stateWhere("the recount for From now") { it.reconfigureCount() == ShareCount.Ready(1) }
            assertEquals(2, asked.distinct().size, "each distinct range is counted, and nothing else")
        }
    }

    @Test
    fun `a count whose read fails is unavailable and the container keeps working`() {
        // Unavailable is also where the count starts, and the state flow drops the Counting in between, so a wait on the
        // state alone passes before the read ran. The read is awaited itself, then Unavailable is the failure's answer.
        val asked = CompletableDeferred<Unit>()
        return onHost(
            queries = counting { _, _ ->
                asked.complete(Unit)
                error("download store unreadable")
            },
        ) { host ->
            host.surfaces.onOpenReconfigure()
            host.stateWhere("the reconfigure surface") { it.reconfigureCount() != null }
            withTimeout(5.seconds) { asked.await() }
            withTimeout(5.seconds) {
                while (host.screen.value.reconfigureCount() != ShareCount.Unavailable) delay(10)
            }
            // Still alive: a later intent lands.
            host.form.onSaveToAlbum(true)
            host.stateWhere("the album edit") {
                ((it.layer as? Layer.Joined)?.surface as? JoinedSurface.Reconfigure)?.range?.saveToAlbum == true
            }
        }
    }

    @Test
    fun `no count is asked for while sharing is off`() {
        var asked = 0
        val spy = Spy()
        return onHost(
            spy,
            queries = counting { _, _ ->
                asked++
                3
            },
        ) { host ->
            host.surfaces.onOpenReconfigure()
            host.stateWhere("the count") { it.reconfigureCount() == ShareCount.Ready(3) }
            host.form.onShareOn(false)
            host.stateWhere("the question") { it.settings()?.askingToStopSharing == true }
            host.settings.onConfirmStopSharing()
            host.stateWhere("sharing off") { it.settings()?.range?.shareOn == false }
            // With nothing shared a range withdraws nothing, so it applies without asking — and costs no read.
            host.form.onRangePreset(RangeChoice.FROM_NOW)
            awaitReconfigures(spy, 2)
            host.stateWhere("the new range shown") { it.settings()?.range?.preset == RangeChoice.CUSTOM }
            assertEquals(1, asked, "a hidden row must not cost a photo-library read")
        }
    }

    // ---- the overlays ------------------------------------------------------------------------------

    @Test
    fun `opening an overlay raises only its own flag`() = onHost { host ->
        host.surfaces.onRenameOpen()
        assertEquals(
            Overlays(renaming = true),
            host.stateWhere("the rename sheet") { it.overlays.renaming }.overlays,
        )
    }

    @Test
    fun `the leave confirmation and the diagnostic sheet each raise only their own`() = onHost { host ->
        host.surfaces.onConfirmLeaveOpen()
        assertEquals(
            Overlays(confirmingLeave = true),
            host.stateWhere("the leave confirmation") { it.overlays.confirmingLeave }.overlays,
        )

        host.surfaces.onReportBugOpen()
        assertEquals(
            Overlays(confirmingLeave = true, reportingBug = true),
            host.stateWhere("the diagnostic sheet") { it.overlays.reportingBug }.overlays,
        )
    }

    @Test
    fun `dismissing an overlay lowers only its own flag`() = onHost { host ->
        host.surfaces.onRenameOpen()
        host.surfaces.onConfirmLeaveOpen()
        host.surfaces.onReportBugOpen()
        host.stateWhere("all three open") {
            it.overlays == Overlays(renaming = true, confirmingLeave = true, reportingBug = true)
        }

        host.surfaces.onRenameDismiss()
        assertEquals(
            Overlays(confirmingLeave = true, reportingBug = true),
            host.stateWhere("the rename sheet dismissed") { !it.overlays.renaming }.overlays,
        )
    }

    @Test
    fun `an overlay is unrenderable while the layer it belongs to is gone`() {
        // A DISPLAY RULE, not a reset. There is nothing to rename without a membership, so a flag that
        // outlived its layer by any route other than the leave is masked out of the projection rather
        // than merely unlikely — and it comes back if the membership does, which is exactly why the
        // leave below resets the cell instead of relying on this.
        val config = MutableStateFlow<EventConfig?>(CONFIG)
        return onHost(config = config) { host ->
            host.surfaces.onRenameOpen()
            host.stateWhere("the rename sheet") { it.overlays.renaming }

            config.value = null
            val gone = host.stateWhere("the membership gone") { it.layer !is Layer.Joined }

            assertTrue(!gone.overlays.renaming, "a dialog was left over a screen whose event is gone")
        }
    }

    @Test
    fun `the invite's QR code opens on request and dismisses touching nothing else`() = onHost { host ->
        host.surfaces.onQrOpen()
        assertEquals(Overlays(showingQr = true), host.stateWhere("the QR shown") { it.overlays.showingQr }.overlays)

        host.surfaces.onQrDismiss()
        assertEquals(Overlays(), host.stateWhere("the QR dismissed") { !it.overlays.showingQr }.overlays)
    }

    @Test
    fun `a shown QR code disappears when the event's photos are deleted or the membership ends`() {
        // A deleted event admits nobody (capability `invite-link`), so the invite it would show is masked
        // the moment the deletion is learned — while the same membership is still on screen with Leave.
        val config = MutableStateFlow<EventConfig?>(CONFIG)
        return onHost(config = config) { host ->
            host.surfaces.onQrOpen()
            host.stateWhere("the QR shown") { it.overlays.showingQr }

            config.value = CONFIG.copy(deleted = true)
            val deleted = host.stateWhere("the event deleted") { (it.layer as? Layer.Joined)?.deleted == true }
            assertTrue(!deleted.overlays.showingQr, "an invite was left over an event that admits nobody")

            config.value = null
            val gone = host.stateWhere("the membership gone") { it.layer !is Layer.Joined }
            assertTrue(!gone.overlays.showingQr, "an invite was left over a screen whose event is gone")
        }
    }

    @Test
    fun `leaving resets the overlay cell so a later rejoin cannot reopen it`() {
        // The reset the mask cannot do: every overlay belongs to the membership being left, and clearing
        // the CELL is what stops a rejoin from reopening a dialog the member dismissed by leaving. The
        // fake leave does not clear the config, so the layer stays Joined — which is what makes this an
        // assertion about the cell rather than about the mask.
        return onHost { host ->
            host.surfaces.onRenameOpen()
            host.stateWhere("the rename sheet") { it.overlays.renaming }

            host.onLeaveEvent()
            val left = host.stateWhere("the sheet closed by the leave") { !it.overlays.renaming }

            assertIs<Layer.Joined>(left.layer, "the mask must not be what closed it")
        }
    }

    // ---- the reconfigure surface -------------------------------------------------------------------

    @Test
    fun `opening the settings surface seeds the form from the persisted membership`() = onHost { host ->
        host.surfaces.onOpenReconfigure()

        val form = host.reconfigureForm()
        // Cutoff on the floor and ceiling on the event end → the whole event, the window's own bounds.
        assertEquals(RangeChoice.EVENT_START, form.preset)
        assertEquals(form.windowStart to form.windowEnd, form.from to form.until)
        assertTrue(form.shareOn)
        assertTrue(form.receiveOn)
    }

    /** Await [n] reconfigures on [spy] — they run on the container's own scope, so a test waits rather than assumes. */
    private suspend fun awaitReconfigures(spy: Spy, n: Int) = withTimeout(5.seconds) {
        while (spy.reconfigures.size < n) delay(5)
    }

    private fun UiState.settings(): JoinedSurface.Reconfigure? =
        (layer as? Layer.Joined)?.surface as? JoinedSurface.Reconfigure

    // ---- settings that apply as they change (capability `manage-membership`) -----------------------

    @Test
    fun `a change applies at once with the event id and the membership's own bounds`() {
        // The id rides WITH the values so a switch landing while the settings are open makes the use-case a no-op
        // rather than overwriting a different membership; a switch never moves the range it does not touch.
        val spy = Spy()
        return onHost(spy) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onSaveToAlbum(true)
            awaitReconfigures(spy, 1)
            val sent = spy.reconfigures.single()
            assertEquals(Reconfigure(EVENT_ID, Direction.Both, CONFIG.minPhotoDate, CONFIG.maxPhotoDate, true), sent)
            val open = host.stateWhere(
                "the album shown on, settings still open",
            ) { it.settings()?.range?.saveToAlbum == true }
            assertEquals(false, open.settings()?.saveFailed)
        }
    }

    @Test
    fun `quick changes apply in order and the last one stands`() {
        val spy = Spy()
        val config = MutableStateFlow<EventConfig?>(CONFIG)
        return onHost(spy, config = config) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onReceiveOn(false)
            host.form.onReceiveOn(true)
            host.form.onReceiveOn(false)
            awaitReconfigures(spy, 3)
            assertEquals(
                listOf(Direction.UploadOnly, Direction.Both, Direction.UploadOnly),
                spy.reconfigures.map { it.direction },
            )
            assertEquals(Direction.UploadOnly, config.value?.direction)
        }
    }

    @Test
    fun `quick changes build on each other even while the membership read lags the save`() {
        // The membership read can trail a save the use-case just made; a change built on it would undo the one before.
        val spy = Spy().apply { configFollows = false }
        return onHost(spy, config = MutableStateFlow(CONFIG.copy(direction = Direction.DownloadOnly))) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onShareOn(true)
            host.form.onReceiveOn(false)
            awaitReconfigures(spy, 2)
            // Built on the stale read, the second would be "share nothing, receive nothing".
            assertEquals(listOf(Direction.Both, Direction.UploadOnly), spy.reconfigures.map { it.direction })
        }
    }

    @Test
    fun `a switch set to what it shows saves nothing`() {
        val spy = Spy()
        return onHost(spy) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onShareOn(true)
            host.form.onSaveToAlbum(CONFIG.saveToAlbum)
            host.form.onReceiveOn(false)
            awaitReconfigures(spy, 1)
            assertEquals(
                listOf(Direction.UploadOnly),
                spy.reconfigures.map { it.direction },
                "only the change is saved",
            )
        }
    }

    @Test
    fun `a change that did not land shows the setting in effect and says so`() {
        // The controls never hold a value that is not saved: the album goes back off, and the settings say why.
        val spy = Spy().apply { reconfigureOutcome = SettingsResult.SaveFailed }
        return onHost(spy) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onSaveToAlbum(true)
            val failed = host.stateWhere("the failed change") { it.settings()?.saveFailed == true }
            assertEquals(false, failed.settings()?.range?.saveToAlbum, "the control shows the setting still in effect")
            assertEquals(1, spy.reconfigures.size)
        }
    }

    @Test
    fun `closing writes nothing`() {
        val spy = Spy()
        return onHost(spy) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.surfaces.onCancelReconfigure()
            host.stateWhere("the settings closed") { (it.layer as? Layer.Joined)?.surface == JoinedSurface.Status }
            assertTrue(spy.reconfigures.isEmpty(), "closing reached the reconfigure use-case")
        }
    }

    @Test
    fun `switching sharing off asks first and applies only on stop sharing`() {
        val spy = Spy()
        return onHost(spy) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onShareOn(false)
            val asking = host.stateWhere("the question") { it.settings()?.askingToStopSharing == true }
            assertEquals(true, asking.settings()?.range?.shareOn, "sharing stays on until the member confirms")
            assertTrue(spy.reconfigures.isEmpty(), "a withdrawal applied before it was confirmed")

            host.settings.onConfirmStopSharing()
            awaitReconfigures(spy, 1)
            assertEquals(Direction.DownloadOnly, spy.reconfigures.single().direction)
            host.stateWhere("the question answered, sharing off") {
                it.settings()?.askingToStopSharing == false && it.settings()?.range?.shareOn == false
            }
        }
    }

    @Test
    fun `keep sharing drops the held change`() {
        val spy = Spy()
        return onHost(spy) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onShareOn(false)
            host.stateWhere("the question") { it.settings()?.askingToStopSharing == true }
            host.settings.onKeepSharing()
            val kept = host.stateWhere("the question gone") { it.settings()?.askingToStopSharing == false }
            assertEquals(true, kept.settings()?.range?.shareOn)
            // A later change proves the dropped one never ran: it is the only reconfigure.
            host.form.onSaveToAlbum(!CONFIG.saveToAlbum)
            awaitReconfigures(spy, 1)
            assertEquals(Direction.Both, spy.reconfigures.single().direction)
        }
    }

    @Test
    fun `widening applies at once and narrowing asks`() {
        // Sharing from the 8th of a 6th–13th event: the whole event widens; a later start narrows.
        val narrowed = CONFIG.copy(minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-08T00:00:00Z")))
        val spy = Spy()
        return onHost(spy, config = MutableStateFlow(narrowed)) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onRangePreset(RangeChoice.EVENT_START)
            awaitReconfigures(spy, 1)
            assertEquals(CONFIG.minPhotoDate, spy.reconfigures.single().from, "widened to the event's start")

            host.form.onRangeCustom(LocalDateTime(2026, 7, 10, 0, 0), null)
            host.stateWhere("the question") { it.settings()?.askingToStopSharing == true }
            assertEquals(1, spy.reconfigures.size, "a narrower range applied before it was confirmed")
        }
    }

    @Test
    fun `a range narrower at one end and wider at the other asks`() {
        val middle = CONFIG.copy(
            minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-08T00:00:00Z")),
            maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-11T00:00:00Z")),
        )
        val spy = Spy()
        return onHost(spy, config = MutableStateFlow(middle)) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            // Earlier start (wider), earlier end (narrower): something is withdrawn, so it asks.
            host.form.onRangeCustom(LocalDateTime(2026, 7, 7, 0, 0), LocalDateTime(2026, 7, 10, 0, 0))
            host.stateWhere("the question") { it.settings()?.askingToStopSharing == true }
            assertTrue(spy.reconfigures.isEmpty())
        }
    }

    @Test
    fun `turning off the last direction leaves a membership that does nothing`() {
        // Receive-only, receiving off: nothing of the member's is shared, so nothing is withdrawn and nothing asks.
        val spy = Spy()
        return onHost(spy, config = MutableStateFlow(CONFIG.copy(direction = Direction.DownloadOnly))) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onReceiveOn(false)
            awaitReconfigures(spy, 1)
            assertEquals(Direction.Neither, spy.reconfigures.single().direction)
        }
    }

    @Test
    fun `a change for a membership no longer current closes the settings`() {
        val spy = Spy().apply { reconfigureOutcome = SettingsResult.NotCurrent }
        return onHost(spy) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onSaveToAlbum(true)
            host.stateWhere("the settings closed") { (it.layer as? Layer.Joined)?.surface == JoinedSurface.Status }
        }
    }

    @Test
    fun `a phone with folder albums opens a stored album-off membership off — and applies the album it turns on`() {
        // Capability `event-album`: an Android membership joined before Android had the album was saved with it off.
        // Settings show that choice, carry the phone's album kind for the note, and apply the album once turned on.
        val spy = Spy()
        return onHost(
            spy,
            config = MutableStateFlow(CONFIG.copy(saveToAlbum = false)),
            albumContents = AlbumContents.RECEIVED,
        ) { host ->
            host.surfaces.onOpenReconfigure()
            val form = host.reconfigureForm()
            assertEquals(AlbumHolds.RECEIVED, form.albumHolds, "the settings know the album is a folder")
            assertEquals(false, form.saveToAlbum, "the stored choice is shown as it was saved")
            host.form.onSaveToAlbum(true)
            awaitReconfigures(spy, 1)
            assertEquals(true, spy.reconfigures.single().saveToAlbum)
            val after = host.stateWhere("the album on") { it.settings()?.range?.saveToAlbum == true }
            assertEquals(
                AlbumHolds.RECEIVED,
                after.settings()?.range?.albumHolds,
                "the reseeded controls keep the album kind",
            )
        }
    }

    @Test
    fun `with the settings closed a form edit reaches no use-case`() {
        val spy = Spy()
        return onHost(spy) { host ->
            host.form.onSaveToAlbum(true)
            host.form.onShareOn(false)
            host.stateWhere("the joined status") { (it.layer as? Layer.Joined)?.surface == JoinedSurface.Status }
            delay(50)
            assertTrue(spy.reconfigures.isEmpty())
        }
    }

    // ---- the form edits ----------------------------------------------------------------------------

    @Test
    fun `a confirmed custom range is shown as the custom range in effect`() = onHost { host ->
        // The coupling is the point: a member who picks dates has chosen CUSTOM by that act. A later start narrows,
        // so it is asked about first.
        val from = LocalDateTime(2026, 7, 8, 9, 0)
        val until = LocalDateTime(2026, 7, 12, 21, 0)
        host.surfaces.onOpenReconfigure()
        host.reconfigureForm()
        host.form.onRangeCustom(from, until)
        host.stateWhere("the question") { it.settings()?.askingToStopSharing == true }
        host.settings.onConfirmStopSharing()
        val shown = host.stateWhere(
            "the custom range",
        ) { it.settings()?.range?.let { f -> f.from == from && f.until == until } == true }
        assertEquals(RangeChoice.CUSTOM, shown.settings()?.range?.preset)
    }

    @Test
    fun `a custom range with one bound keeps the other one in effect`() {
        val middle = CONFIG.copy(
            minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-08T09:00:00Z")),
            maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-12T21:00:00Z")),
        )
        val spy = Spy()
        return onHost(spy, config = MutableStateFlow(middle)) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onRangeCustom(null, LocalDateTime(2026, 7, 11, 21, 0))
            host.stateWhere("the question") { it.settings()?.askingToStopSharing == true }
            host.settings.onConfirmStopSharing()
            awaitReconfigures(spy, 1)
            assertEquals(middle.minPhotoDate, spy.reconfigures.single().from, "the start in effect is kept")
        }
    }

    @Test
    fun `a preset after a custom range widens back to the whole event`() {
        val middle = CONFIG.copy(minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-08T09:00:00Z")))
        val spy = Spy()
        return onHost(spy, config = MutableStateFlow(middle)) { host ->
            host.surfaces.onOpenReconfigure()
            assertEquals(RangeChoice.CUSTOM, host.reconfigureForm().preset)
            host.form.onRangePreset(RangeChoice.EVENT_START)
            awaitReconfigures(spy, 1)
            assertEquals(
                CONFIG.minPhotoDate to CONFIG.maxPhotoDate,
                spy.reconfigures.single().let { it.from to it.until },
            )
            host.stateWhere("the whole event shown") { it.settings()?.range?.preset == RangeChoice.EVENT_START }
        }
    }

    // ---- rename and diagnostics --------------------------------------------------------------------

    @Test
    fun `renaming delegates with the event id the dialog was opened for`() {
        val spy = Spy()
        return onHost(spy) { host ->
            host.onRenameEvent(EVENT_ID, "Anna's Wedding")
            withTimeout(5.seconds) {
                while (spy.renames.isEmpty()) kotlinx.coroutines.yield()
            }
            assertEquals(listOf(EVENT_ID to "Anna's Wedding"), spy.renames)
        }
    }

    @Test
    fun `a rename for an id that names no event renames nothing`() {
        val spy = Spy()
        return onHost(spy) { host ->
            host.onRenameEvent("not-an-event", "Anna's Wedding")
            // One rename at a time: the event's own is retried until the refused one has released the guard.
            withTimeout(5.seconds) {
                while (spy.renames.isEmpty()) {
                    host.onRenameEvent(EVENT_ID, "Anna's Wedding")
                    kotlinx.coroutines.delay(10)
                }
            }
            assertEquals(setOf(EVENT_ID to "Anna's Wedding"), spy.renames.toSet(), "only the event's own reached it")
        }
    }

    @Test
    fun `consuming the rename status clears the latch`() {
        // The sheet closes itself on a success and consumes it: the next edit begins with nothing answered.
        val spy = Spy().apply { renameAnswer = RenameResult.Renamed }
        return onHost(spy) { host ->
            host.onRenameEvent(EVENT_ID, "Anna's Wedding")
            host.stateWhere("the rename answered") { (it.layer as? Layer.Joined)?.renameState == RenameState.Succeeded }
            host.onRenameStatusConsumed()
            host.stateWhere("the answer consumed") { (it.layer as? Layer.Joined)?.renameState == RenameState.Idle }
        }
    }

    @Test
    fun `every state says where this build’s bug report goes`() =
        onHost(reportDestination = ReportDestination.THIS_DEVICE) { host ->
            withTimeout(5.seconds) {
                while (host.screen.value.reportDestination != ReportTarget.THIS_DEVICE) {
                    kotlinx.coroutines.yield()
                }
            }
            assertEquals(ReportTarget.THIS_DEVICE, host.screen.value.reportDestination)
        }

    @Test
    fun `a build with a channel forwards the note and the surface it was sent from`() {
        val spy = Spy()
        return onHost(
            spy,
            sendDiagnostics = { note, screen ->
                spy.diagnostics += note to screen
                ReportOutcome.SENT
            },
        ) { host ->
            host.onSendDiagnostics("photos are not arriving", "joined")
            withTimeout(5.seconds) {
                while (spy.diagnostics.isEmpty()) kotlinx.coroutines.yield()
            }
            // The host's default membership is in sync with nothing to count: the counts line it shows rides along.
            val shown = mapOf(DiagnosticKeys.SHOWN_SHARED to "0/0", DiagnosticKeys.SHOWN_RECEIVED to "0/0")
            assertEquals(listOf("photos are not arriving" to ReportContext("joined", shown)), spy.diagnostics)
        }
    }
}
