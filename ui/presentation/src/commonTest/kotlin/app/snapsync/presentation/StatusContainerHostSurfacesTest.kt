package app.snapsync.presentation

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.FromChoice
import app.snapsync.model.PermissionStatus
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.UntilChoice
import app.snapsync.model.UserCommands
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.feature.status.SyncStatusSource
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val EVENT_ID = "11111111-1111-4111-8111-111111111111"

/** A membership whose cutoff sits exactly on the floor and ceiling, so the form seeds to both presets. */
private val CONFIG = EventConfig(
    eventId = EVENT_ID,
    name = "Anna's Birthday",
    minPhotoDate = captureCutoff("2026-07-06T14:32:11Z"),
    startsAt = eventStart("2026-07-06T14:32:11Z"),
    endsAt = eventEnd("2026-07-13T14:32:11Z"),
    maxPhotoDate = captureCeiling("2026-07-13T14:32:11Z"),
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
    var renameResets = 0
    val reconfigures = mutableListOf<Reconfigure>()
    val diagnostics = mutableListOf<Pair<String, String>>()
}

/**
 * The **overlay and settings surfaces** of the status container — the rename dialog, the leave
 * confirmation, the diagnostic sheet, and the reconfigure form (capabilities `event-rename`,
 * `leave-event`, `diagnostic-logging`, `reconfigure-membership`).
 *
 * `StatusContainerHostTest` covers the join gate, permissions, direction and sync health exhaustively;
 * this whole family was reached by nothing. Each command here "reduces and nothing more" — which is
 * exactly why an untested one fails invisibly: a navigation act that quietly touched a port, or a
 * pre-fill that tracked a background refresh instead of freezing, changes no assertion anywhere else.
 *
 * ⚠️ Driven on a REAL container over a real scope rather than through `orbit-test`'s `test()` harness,
 * which the neighbouring file uses. The harness substitutes its own container, so `container.stateFlow`
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
        sendDiagnostics: (suspend (String, String) -> Unit)? = null,
    ) = StatusContainerHost(
        StatusSources(FakeSync(), MutableStateFlow(PermissionStatus.GRANTED), config),
        scope,
        commands = UserCommands(
            reconfigure = { id, direction, from, until, album ->
                spy.reconfigures += Reconfigure(id, direction, from, until, album)
            },
            rename = { id, name -> spy.renames += id to name },
            resetRename = { spy.renameResets++ },
            sendDiagnostics = sendDiagnostics,
        ),
        cutoffFormatter = CutoffFormatter(
            now = { Instant.parse("2026-07-09T12:00:00Z") },
            zone = TimeZone.UTC,
        ),
    )

    /** Await the first state satisfying [predicate] — failing loudly rather than hanging if none comes. */
    private suspend fun StatusContainerHost.stateWhere(
        what: String,
        predicate: (UiState) -> Boolean,
    ): UiState = withTimeout(5.seconds) {
        try {
            container.stateFlow.first(predicate)
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("never reached: $what (last was ${container.stateFlow.value})", timeout)
        }
    }

    private suspend fun StatusContainerHost.reconfigureForm(): RangeForm {
        val state = stateWhere("the reconfigure surface") {
            (it.layer as? Layer.Joined)?.surface is JoinedSurface.Reconfigure
        }
        val joined = assertIs<Layer.Joined>(state.layer)
        return assertIs<JoinedSurface.Reconfigure>(joined.surface).form
    }

    private fun onHost(
        spy: Spy = Spy(),
        config: MutableStateFlow<EventConfig?> = MutableStateFlow(CONFIG),
        sendDiagnostics: (suspend (String, String) -> Unit)? = null,
        body: suspend (StatusContainerHost) -> Unit,
    ) = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                body(host(scope, spy, config, sendDiagnostics))
            } finally {
                scope.cancel()
            }
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
        // Cutoff on the floor and ceiling on the event end → both presets, no custom values.
        assertEquals(FromChoice.EVENT_START, form.fromPreset)
        assertEquals(UntilChoice.EVENT_END, form.untilPreset)
        assertNull(form.fromCustom)
        assertNull(form.untilCustom)
        assertTrue(form.shareOn)
        assertTrue(form.receiveOn)
    }

    @Test
    fun `the pre-fill is a snapshot so a refresh landing mid-edit leaves the controls alone`() {
        // Seeding happens when the surface OPENS, not in the reduction. A foreground refresh updates the
        // heading; it must not reach into the controls in the member's hand and move them.
        val config = MutableStateFlow<EventConfig?>(CONFIG)
        return onHost(config = config) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onSaveToAlbum(true)
            host.stateWhere("the album edit") {
                ((it.layer as? Layer.Joined)?.surface as? JoinedSurface.Reconfigure)?.form?.saveToAlbum == true
            }

            // A refresh lands carrying a different membership shape than the form was seeded from.
            config.value = CONFIG.copy(saveToAlbum = false, direction = Direction.DownloadOnly)
            host.stateWhere("the refreshed membership") {
                (it.layer as? Layer.Joined)?.membership?.direction == Direction.DownloadOnly
            }

            val form = host.reconfigureForm()
            assertTrue(form.saveToAlbum, "the refresh reset an edit the member had already made")
            assertTrue(form.shareOn, "the refresh moved a control the member was not touching")
        }
    }

    @Test
    fun `cancelling closes the surface discarding the edits and touching no port`() {
        val spy = Spy()
        return onHost(spy) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onSaveToAlbum(true)
            host.surfaces.onCancelReconfigure()

            host.stateWhere("the surface closed") {
                (it.layer as? Layer.Joined)?.surface == JoinedSurface.Status
            }
            assertTrue(spy.reconfigures.isEmpty(), "cancel reached the reconfigure use-case")
        }
    }

    @Test
    fun `saving carries the edits and the event id the surface was opened for`() {
        // The id rides WITH the values so a switch landing mid-edit makes the use-case a no-op rather
        // than overwriting a different membership.
        val spy = Spy()
        return onHost(spy) { host ->
            host.surfaces.onOpenReconfigure()
            host.reconfigureForm()
            host.form.onShareOn(false)
            host.form.onSaveToAlbum(true)
            host.stateWhere("both edits applied") {
                val form = ((it.layer as? Layer.Joined)?.surface as? JoinedSurface.Reconfigure)?.form
                form?.saveToAlbum == true && form.shareOn == false
            }

            host.onReconfigure()
            host.stateWhere("the surface closed") {
                (it.layer as? Layer.Joined)?.surface == JoinedSurface.Status
            }

            val sent = spy.reconfigures.single()
            assertEquals(EVENT_ID, sent.eventId)
            assertEquals(
                Direction.DownloadOnly,
                sent.direction,
                "share off with receive on is a download-only membership",
            )
            assertTrue(sent.saveToAlbum)
        }
    }

    @Test
    fun `saving with no membership reaches no use-case`() {
        val spy = Spy()
        return onHost(spy, config = MutableStateFlow(null)) { host ->
            host.onReconfigure()
            host.stateWhere("the create layer") { it.layer !is Layer.Joined }
            assertTrue(spy.reconfigures.isEmpty())
        }
    }

    // ---- the form edits ----------------------------------------------------------------------------

    @Test
    fun `a custom date implies its own preset on both ends of the range`() = onHost { host ->
        // The coupling is the point: a member who picks a date has chosen CUSTOM by that act, so the
        // preset cannot be left on EVENT_START with a custom value sitting beside it unused.
        val from = LocalDateTime(2026, 7, 8, 9, 0)
        val until = LocalDateTime(2026, 7, 12, 21, 0)
        host.surfaces.onOpenReconfigure()
        host.reconfigureForm()
        host.form.onFromCustom(from)
        host.form.onUntilCustom(until)

        host.stateWhere("both custom dates") {
            val form = ((it.layer as? Layer.Joined)?.surface as? JoinedSurface.Reconfigure)?.form
            form?.fromCustom == from && form.untilCustom == until
        }
        val form = host.reconfigureForm()
        assertEquals(FromChoice.CUSTOM, form.fromPreset)
        assertEquals(UntilChoice.CUSTOM, form.untilPreset)
    }

    @Test
    fun `a preset tap replaces a custom choice without clearing the value behind it`() = onHost { host ->
        val from = LocalDateTime(2026, 7, 8, 9, 0)
        host.surfaces.onOpenReconfigure()
        host.reconfigureForm()
        host.form.onFromCustom(from)
        host.stateWhere("the custom date") {
            ((it.layer as? Layer.Joined)?.surface as? JoinedSurface.Reconfigure)?.form?.fromCustom == from
        }

        host.form.onFromPreset(FromChoice.EVENT_START)
        host.stateWhere("the preset back") {
            ((it.layer as? Layer.Joined)?.surface as? JoinedSurface.Reconfigure)
                ?.form?.fromPreset == FromChoice.EVENT_START
        }

        // Kept, so switching back to CUSTOM restores what the member picked rather than an empty field.
        assertEquals(from, host.reconfigureForm().fromCustom)
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
    fun `consuming the rename status clears the latch`() {
        // Suspending on the far side: the screen may start the next rename immediately, so the clear has
        // to have happened by the time the call returns or a second rename begins with the previous
        // Succeeded still latched.
        val spy = Spy()
        return onHost(spy) { host ->
            host.onRenameStatusConsumed()
            withTimeout(5.seconds) {
                while (spy.renameResets == 0) kotlinx.coroutines.yield()
            }
            assertEquals(1, spy.renameResets)
        }
    }

    @Test
    fun `a build with no reporting channel offers no diagnostics gesture at all`() = onHost { host ->
        // Not a no-op command: the affordance must not EXIST, so the screen wires no gesture rather than
        // wiring one that silently does nothing.
        assertNull(host.onSendDiagnostics)
    }

    @Test
    fun `a build with a channel forwards the note and the surface it was sent from`() {
        val spy = Spy()
        return onHost(spy, sendDiagnostics = { note, screen -> spy.diagnostics += note to screen }) { host ->
            assertNotNull(host.onSendDiagnostics).invoke("photos are not arriving", "joined")
            withTimeout(5.seconds) {
                while (spy.diagnostics.isEmpty()) kotlinx.coroutines.yield()
            }
            assertEquals(listOf("photos are not arriving" to "joined"), spy.diagnostics)
        }
    }
}
