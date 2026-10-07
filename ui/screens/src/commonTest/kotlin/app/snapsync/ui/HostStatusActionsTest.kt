@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runComposeUiTest
import app.snapsync.feature.status.readmodel.NetworkStatusSource
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.GalleryAccess
import app.snapsync.model.JoinCommit
import app.snapsync.model.JoinLoad
import app.snapsync.model.JoinPhase
import app.snapsync.model.JoinedSurface
import app.snapsync.model.Layer
import app.snapsync.model.NetworkAccess
import app.snapsync.model.RangeChoice
import app.snapsync.model.ReconfigureOutcome
import app.snapsync.model.ScreenMessage
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.model.SyncHealth
import app.snapsync.model.SyncStatus
import app.snapsync.model.UiState
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.model.VersionRefusal
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
import app.snapsync.presentation.onIntent
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.ui.components.resources.menu
import app.snapsync.ui.components.resources.network_blocked
import app.snapsync.ui.components.resources.share_range_change
import app.snapsync.ui.components.resources.status_allow_access
import app.snapsync.ui.components.resources.status_allow_access_settings
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.album_toggle
import app.snapsync.ui.resources.allow_full_access
import app.snapsync.ui.resources.cancel
import app.snapsync.ui.resources.choose_more_photos
import app.snapsync.ui.resources.create_button
import app.snapsync.ui.resources.create_join_hint
import app.snapsync.ui.resources.footer_settings
import app.snapsync.ui.resources.invite_caption
import app.snapsync.ui.resources.join_access_dismiss
import app.snapsync.ui.resources.join_access_info
import app.snapsync.ui.resources.join_button
import app.snapsync.ui.resources.join_button_allow
import app.snapsync.ui.resources.leave_cancel
import app.snapsync.ui.resources.leave_confirm
import app.snapsync.ui.resources.leave_event
import app.snapsync.ui.resources.menu_privacy
import app.snapsync.ui.resources.menu_website
import app.snapsync.ui.resources.message_app_not_genuine
import app.snapsync.ui.resources.message_device_unverifiable
import app.snapsync.ui.resources.message_report_this
import app.snapsync.ui.resources.mobile_data_toggle
import app.snapsync.ui.resources.range_from_now
import app.snapsync.ui.resources.range_whole_event
import app.snapsync.ui.resources.receive_toggle
import app.snapsync.ui.resources.rename_event
import app.snapsync.ui.resources.report_placeholder
import app.snapsync.ui.resources.report_problem
import app.snapsync.ui.resources.report_seed_device_unverifiable
import app.snapsync.ui.resources.report_send
import app.snapsync.ui.resources.report_sent
import app.snapsync.ui.resources.retry
import app.snapsync.ui.resources.save
import app.snapsync.ui.resources.share_invite
import app.snapsync.ui.resources.share_toggle
import app.snapsync.ui.resources.show_qr
import app.snapsync.ui.resources.stop_sharing_confirm
import app.snapsync.ui.resources.store_app_store
import app.snapsync.ui.resources.switch_confirm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import app.snapsync.ui.components.resources.Res as ComponentRes

/** The event the joined tests are members of. */
private const val JOINED_ID = "11111111-1111-4111-8111-111111111111"

/** A second event, for the join gate and the switch. */
private const val OTHER_ID = "22222222-2222-4222-8222-222222222222"

private const val STORE_URL = "https://apps.apple.com/de/app/snapsync/id0000000000"

private val MEMBERSHIP = EventConfig(
    eventId = JOINED_ID,
    name = "Anna's Birthday",
    minPhotoDate = captureCutoff("2026-07-04T18:00:00Z"),
    startsAt = eventStart("2026-07-04T18:00:00Z"),
    endsAt = eventEnd("2026-07-20T18:00:00Z"),
    maxPhotoDate = captureCeiling("2026-07-20T18:00:00Z"),
    deletesAt = deletesAt("2099-12-31T00:00:00Z"),
)

/** What the details load answers for [OTHER_ID]: an event that has started and runs past "now". */
private val OTHER_EVENT = JoinLoad.Found(
    "Anna's Wedding",
    eventStart("2026-07-04T18:00:00Z"),
    eventEnd("2026-07-20T18:00:00Z"),
    deletesAt("2026-08-03T18:00:00Z"),
)

private fun linkTo(eventId: String) = encodeEventUrl(EventLinkPayload(eventId))

/**
 * The status screen's ONE tap → intent table, [statusActions], clicked through the real screen over a real
 * container (spec `sync-status`, "The screen's callback bundle is built in one place").
 *
 * The other screen suites hand `StatusScreen` a forged `UiState` and a spy bundle, and the container suites fire
 * intents directly — so between them, nothing ever ran the table that joins the two. That table was written out by
 * hand in every host, and the copies had drifted: only the iOS app bound the update-required store button. Clicking
 * is legitimate HERE for exactly the reason it is not in an integration test: the bundle clicked is the bundle every
 * host ships, because every host now takes it from this one function.
 *
 * Each test is chosen so that crossing a binding with its SIBLING fails it — open with dismiss, confirm with cancel,
 * request with settings. The assertion is always an outcome the container owns: the state it reduced to, or the
 * command it fired through [UserCommands]. The container runs on a real scope, so every assertion waits for the
 * intent to land rather than assuming it already has.
 */
class HostStatusActionsTest {

    /** A real container over plain cells, recording every command it fires. */
    private class Rig(
        config: EventConfig? = null,
        permission: GalleryAccess = GalleryAccess.GRANTED,
        refusal: VersionRefusal? = null,
        diagnostics: Boolean = false,
        private val details: suspend (String) -> JoinLoad = { OTHER_EVENT },
        network: NetworkAccess = NetworkAccess.Online(restricted = false),
        deviceRefusal: app.snapsync.model.DeviceRefusal? = null,
    ) {
        val config = MutableStateFlow(config)
        val permission = MutableStateFlow(permission)
        private val firedCell = MutableStateFlow<List<String>>(emptyList())
        val fired: List<String> get() = firedCell.value
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private fun record(what: String) = firedCell.update { it + what }

        val host = StatusContainerHost(
            StatusSources(
                sync = object : SyncStatusSource {
                    override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Loading)
                },
                permission = this.permission,
                config = this.config,
                versionRefusal = MutableStateFlow(refusal),
                verification = app.snapsync.presentation.DeviceVerification(refusal = MutableStateFlow(deviceRefusal)),
                store = StoreLink(STORE_URL, StoreKind.APP_STORE),
                network = object : NetworkStatusSource {
                    override val access: StateFlow<NetworkAccess> = MutableStateFlow(network)
                    override val returned: Flow<Unit> = emptyFlow()
                },
            ),
            scope = scope,
            cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-06T12:00:00Z") }, zone = TimeZone.UTC),
            commands = UserCommands(
                leave = {
                    record("leave")
                    this.config.value = null
                },
                create = { name, _, _ -> record("create:$name") },
                commitJoin = { join ->
                    record("commitJoin:${join.eventId}:${join.direction}:${join.saveToAlbum}")
                    JoinCommit.Failed
                },
                share = { url, _ -> record("share:$url") },
                requestAccess = { record("requestAccess") },
                openSettings = { record("openSettings") },
                openLink = { record("openLink:$it") },
                choosePhotos = { record("choosePhotos") },
                reconfigure = { eventId, direction, _, _, _ ->
                    record(
                        "reconfigure:$eventId:$direction",
                    )
                    ReconfigureOutcome.Saved
                },
                rename = { eventId, name -> record("rename:$eventId:$name") },
                resetRename = { record("resetRename") },
                sendDiagnostics = { note, context ->
                    // A report opened from "Report this" is marked: only it carries the refused verification's facts.
                    record("sendDiagnostics:$note" + if (context.verification) " [verification]" else "")
                    app.snapsync.model.ReportOutcome.SENT
                },
                setMobileData = { on ->
                    record("setMobileData:$on")
                    true
                },
                restoreEventKey = {
                    record("restoreEventKey")
                    false
                },
            ),
            queries = UserQueries(loadJoinDetails = { id, _ -> details(id) }, shareableCount = { _, _ -> null }),
            diagnostics = StatusDiagnostics(log = {}, onIntentError = {}),
        )

        val state: UiState get() = host.container.stateFlow.value
    }

    /** Compose [rig]'s real screen with the shared factory's bundle, as every host does. */
    private fun ComposeUiTest.show(rig: Rig) {
        setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                val state by rig.host.container.stateFlow.collectAsState()
                WithoutKeyboardInsets {
                    StatusScreen(
                        state = state,
                        cutoff = CutoffFormatter(now = { Instant.parse("2026-07-06T12:00:00Z") }, zone = TimeZone.UTC),
                        actions = statusActions(rig.host::onIntent),
                    )
                }
            }
        }
    }

    /** Run [block] on a fresh rig, cancelling its scope however the test ends. */
    private fun rigTest(rig: Rig, block: ComposeUiTest.(Rig) -> Unit) = runComposeUiTest {
        try {
            show(rig)
            block(rig)
        } finally {
            rig.scope.cancel()
        }
    }

    /** Wait for the container to reduce to a state matching [predicate], then for the screen to catch up. */
    private fun ComposeUiTest.awaitState(rig: Rig, predicate: (UiState) -> Boolean) {
        waitUntil(timeoutMillis = 5_000) { predicate(rig.state) }
        waitForIdle()
    }

    /** Wait for the container to fire [command] — commands run in the container's intents, off this thread. */
    private fun ComposeUiTest.awaitFired(rig: Rig, command: String) {
        waitUntil(timeoutMillis = 5_000) { command in rig.fired }
    }

    private fun rig(
        config: EventConfig? = null,
        permission: GalleryAccess = GalleryAccess.GRANTED,
        refusal: VersionRefusal? = null,
        diagnostics: Boolean = false,
        details: suspend (String) -> JoinLoad = { OTHER_EVENT },
        network: NetworkAccess = NetworkAccess.Online(restricted = false),
        deviceRefusal: app.snapsync.model.DeviceRefusal? = null,
    ) = Rig(config, permission, refusal, diagnostics, details, network, deviceRefusal)

    private val ready: (UiState) -> Boolean =
        { (it.joining?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready }

    private val UiState.joined get() = layer as? Layer.Joined
    private val UiState.joining get() = layer as? Layer.JoiningEvent

    // ---- the update-required store button (capability `app-update-required`) — the binding that had drifted ----

    @Test
    fun `the store button opens the store link through the container`() =
        rigTest(rig(refusal = VersionRefusal("0.4"))) { rig ->
            awaitState(rig) { it.layer is Layer.UpdateRequired }
            onNodeWithText(str(Res.string.store_app_store)).performClick()
            awaitFired(rig, "openLink:$STORE_URL")
        }

    // ---- the joined layer ----

    @Test
    fun `leave opens its confirmation — Stay dismisses it — and Leave fires the leave`() =
        rigTest(rig(config = MEMBERSHIP)) { rig ->
            awaitState(rig) { it.joined != null }
            onNodeWithText(str(Res.string.leave_event)).performClick()
            awaitState(rig) { it.overlays.confirmingLeave }
            onNodeWithText(str(Res.string.leave_cancel)).performClick()
            awaitState(rig) { !it.overlays.confirmingLeave }
            assertEquals(emptyList(), rig.fired)

            onNodeWithText(str(Res.string.leave_event)).performClick()
            awaitState(rig) { it.overlays.confirmingLeave }
            onNode(hasText(str(Res.string.leave_confirm)) and hasAnyAncestor(isDialog())).performClick()
            awaitFired(rig, "leave")
        }

    @Test
    fun `share hands the rendered invite link to the platform share`() =
        rigTest(rig(config = MEMBERSHIP)) { rig ->
            awaitState(rig) { it.joined != null }
            val invite = rig.state.joined!!.inviteUrl
            onNodeWithText(str(Res.string.share_invite)).performClick()
            awaitFired(rig, "share:$invite")
        }

    @Test
    fun `show QR code opens the invite's sheet and swiping it away closes it touching nothing`() =
        rigTest(rig(config = MEMBERSHIP)) { rig ->
            awaitState(rig) { it.joined != null }
            onNodeWithText(str(Res.string.show_qr)).performClick()
            awaitState(rig) { it.overlays.showingQr }
            onNodeWithText(str(Res.string.invite_caption)).performTouchInput { swipeDown() }
            awaitState(rig) { !it.overlays.showingQr }
            assertEquals(emptyList(), rig.fired)
        }

    @Test
    fun `the pen opens the rename sheet — Cancel dismisses it — and Save renames`() =
        rigTest(rig(config = MEMBERSHIP)) { rig ->
            awaitState(rig) { it.joined != null }
            onNodeWithContentDescription(str(Res.string.rename_event)).performClick()
            awaitState(rig) { it.overlays.renaming }
            onNodeWithText(str(Res.string.cancel)).performClick()
            awaitState(rig) { !it.overlays.renaming }
            // Dismissing also clears the rename latch.
            awaitFired(rig, "resetRename")

            onNodeWithContentDescription(str(Res.string.rename_event)).performClick()
            awaitState(rig) { it.overlays.renaming }
            onNode(hasSetTextAction()).performTextClearance()
            onNode(hasSetTextAction()).performTextInput("Anna's Party")
            onNodeWithText(str(Res.string.save)).performClick()
            awaitFired(rig, "rename:$JOINED_ID:Anna's Party")
        }

    @Test
    fun `settings open from the footer — sharing off asks and Stop sharing applies — and a tap above closes them`() =
        rigTest(rig(config = MEMBERSHIP)) { rig ->
            awaitState(rig) { it.joined != null }
            onNodeWithText(str(Res.string.footer_settings)).performClick()
            awaitState(rig) { it.joined?.surface is JoinedSurface.Reconfigure }

            // Sharing off withdraws photos: it is asked about, and nothing applies until the member confirms.
            onNodeWithText(str(Res.string.share_toggle)).performClick()
            awaitState(rig) { (it.joined?.surface as? JoinedSurface.Reconfigure)?.askingToStopSharing == true }
            assertEquals(emptyList(), rig.fired)
            onNode(hasText(str(Res.string.stop_sharing_confirm)) and hasAnyAncestor(isDialog())).performClick()
            awaitFired(rig, "reconfigure:$JOINED_ID:DownloadOnly")

            // Closing writes nothing more.
            onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(centerX, 5f)) }
            awaitState(rig) { it.joined != null && it.joined?.surface !is JoinedSurface.Reconfigure }
            assertEquals(listOf("reconfigure:$JOINED_ID:DownloadOnly"), rig.fired)
        }

    // ---- the access prompts (capabilities `photo-access`, `photo-access`) ----

    @Test
    fun `a never-asked grant’s prompt requests access`() =
        rigTest(rig(config = MEMBERSHIP, permission = GalleryAccess.NOT_DETERMINED)) { rig ->
            awaitState(rig) { it.joined != null }
            onNodeWithText(str(ComponentRes.string.status_allow_access)).performClick()
            awaitFired(rig, "requestAccess")
            assertEquals(listOf("requestAccess"), rig.fired)
        }

    @Test
    fun `a denied grant’s prompt opens Settings`() =
        rigTest(rig(config = MEMBERSHIP, permission = GalleryAccess.DENIED)) { rig ->
            awaitState(rig) { it.joined != null }
            onNodeWithText(str(ComponentRes.string.status_allow_access_settings)).performClick()
            awaitFired(rig, "openSettings")
            assertEquals(listOf("openSettings"), rig.fired)
        }

    @Test
    fun `a blocked network’s status line opens Settings`() =
        rigTest(rig(config = MEMBERSHIP, network = NetworkAccess.Blocked)) { rig ->
            awaitState(rig) { (it.layer as? Layer.Joined)?.health is SyncHealth.NoNetwork }
            onNodeWithText(str(ComponentRes.string.network_blocked)).performClick()
            awaitFired(rig, "openSettings")
            assertEquals(listOf("openSettings"), rig.fired)
        }

    @Test
    fun `a blocked network’s notice on the create screen opens Settings`() =
        rigTest(rig(network = NetworkAccess.Blocked)) { rig ->
            awaitState(rig) { (it.layer as? Layer.CreateEvent)?.network != null }
            onNodeWithText(str(ComponentRes.string.network_blocked)).performClick()
            awaitFired(rig, "openSettings")
            assertEquals(listOf("openSettings"), rig.fired)
        }

    @Test
    fun `a partial grant offers the picker and Settings — each its own`() =
        rigTest(rig(config = MEMBERSHIP, permission = GalleryAccess.LIMITED)) { rig ->
            awaitState(rig) { it.joined?.canChoosePhotos == true }
            onNodeWithText(str(Res.string.choose_more_photos)).performClick()
            awaitFired(rig, "choosePhotos")
            assertEquals(listOf("choosePhotos"), rig.fired)
            onNodeWithText(str(Res.string.allow_full_access)).performClick()
            awaitFired(rig, "openSettings")
        }

    // ---- the create layer ----

    @Test
    fun `create submits the typed name`() = rigTest(rig()) { rig ->
        awaitState(rig) { it.layer is Layer.CreateEvent }
        // Create is enabled only once the range is complete too (capability `create-event`).
        completeForm("My Party")
        onNodeWithText(str(Res.string.create_button)).performClick()
        awaitFired(rig, "create:My Party")
    }

    // ---- the join gate (capability `join-event`) ----

    @Test
    fun `an opened link’s gate edits its form and Join commits the choice`() = rigTest(rig()) { rig ->
        rig.host.onOpenUrl(linkTo(OTHER_ID))
        awaitState(rig, ready)

        // Each range edit, so a preset crossed with a custom pick lands in the wrong field.
        onNodeWithContentDescription(str(ComponentRes.string.share_range_change)).performClick()
        onNodeWithText(str(Res.string.range_from_now)).performClick()
        awaitState(rig) { it.joining?.form?.preset == RangeChoice.FROM_NOW }
        onNodeWithContentDescription(str(ComponentRes.string.share_range_change)).performClick()
        onNodeWithText(str(Res.string.save)).performClick()
        awaitState(rig) {
            it.joining?.form?.let { f -> f.preset == RangeChoice.CUSTOM && f.customFrom != null && f.customUntil != null } == true
        }
        onNodeWithContentDescription(str(ComponentRes.string.share_range_change)).performClick()
        onNode(hasText(str(Res.string.range_whole_event)) and isSelectable()).performClick()
        awaitState(rig) { it.joining?.form?.preset == RangeChoice.WHOLE_EVENT }
        // The participation switches, likewise one field each.
        onNodeWithText(str(Res.string.share_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.shareOn == false }
        onNodeWithText(str(Res.string.share_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.shareOn == true }
        onNodeWithText(str(Res.string.receive_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.receiveOn == false }
        onNodeWithText(str(Res.string.receive_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.receiveOn == true }
        // The album opt-in defaults ON, so the tap turns it off.
        onNodeWithText(str(Res.string.album_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.saveToAlbum == false }
        // Share off with receive on: a direction only the two switches together can produce.
        onNodeWithText(str(Res.string.share_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.shareOn == false }

        onNodeWithText(str(Res.string.join_button)).performClick()
        waitUntil(timeoutMillis = 5_000) { rig.fired.any { it.startsWith("commitJoin:") } }
        assertEquals(listOf("commitJoin:$OTHER_ID:DownloadOnly:false"), rig.fired)
    }

    @Test
    fun `Cancel discards the pending join without committing`() = rigTest(rig()) { rig ->
        rig.host.onOpenUrl(linkTo(OTHER_ID))
        awaitState(rig, ready)
        onNodeWithText(str(Res.string.cancel)).performClick()
        awaitState(rig) { it.layer is Layer.CreateEvent }
        assertEquals(emptyList(), rig.fired)
    }

    @Test
    fun `a never-asked guest’s Join and allow photos requests access and commits`() =
        rigTest(rig(permission = GalleryAccess.NOT_DETERMINED)) { rig ->
            rig.host.onOpenUrl(linkTo(OTHER_ID))
            awaitState(rig, ready)
            // The explanation is on request, and reading it raises nothing.
            onNodeWithContentDescription(str(Res.string.join_access_info)).performClick()
            onNodeWithText(str(Res.string.join_access_dismiss)).performClick()
            assertEquals(emptyList(), rig.fired)
            onNodeWithText(str(Res.string.join_button_allow)).performClick()
            waitUntil(timeoutMillis = 5_000) { rig.fired.any { it.startsWith("commitJoin:") } }
            assertEquals("requestAccess", rig.fired.first())
        }

    @Test
    fun `Retry after a failed load loads again`() {
        var loads = 0
        rigTest(rig(details = { if (++loads == 1) JoinLoad.Failed else OTHER_EVENT })) { rig ->
            rig.host.onOpenUrl(linkTo(OTHER_ID))
            awaitState(rig) { it.joining?.phase == JoinPhase.LoadFailed }
            onNodeWithText(str(Res.string.retry)).performClick()
            awaitState(rig, ready)
            assertEquals(2, loads)
        }
    }

    @Test
    fun `Retry after a failed commit commits again`() = rigTest(rig()) { rig ->
        rig.host.onOpenUrl(linkTo(OTHER_ID))
        awaitState(rig, ready)
        onNodeWithText(str(Res.string.join_button)).performClick()
        awaitState(rig) { (it.joining?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.CommitFailed }
        onNodeWithText(str(Res.string.retry)).performClick()
        waitUntil(timeoutMillis = 5_000) { rig.fired.count { it.startsWith("commitJoin:") } == 2 }
    }

    // ---- the switch confirmation (a different event scanned while joined) ----

    @Test
    fun `the switch confirmation’s Cancel keeps the membership and Switch leaves it`() =
        rigTest(rig(config = MEMBERSHIP)) { rig ->
            rig.host.onOpenUrl(linkTo(OTHER_ID))
            awaitState(rig) { it.joined?.pendingSwitch != null }
            onNodeWithText(str(Res.string.cancel)).performClick()
            awaitState(rig) { it.joined != null && it.joined?.pendingSwitch == null }
            assertEquals(emptyList(), rig.fired)

            rig.host.onOpenUrl(linkTo(OTHER_ID))
            awaitState(rig) { it.joined?.pendingSwitch != null }
            onNodeWithText(str(Res.string.switch_confirm)).performClick()
            awaitFired(rig, "leave")
            // The leave cleared the membership, so the gate re-renders as the new event's own join surface.
            awaitState(rig) { it.joining?.eventId == OTHER_ID }
        }

    // ---- the app menu (capabilities `sync-status`, `privacy-security`) ----

    @Test
    fun `the menu's report opens the sheet — Send sends — and the word on it can be tapped away`() =
        rigTest(rig(diagnostics = true)) { rig ->
            awaitState(rig) { it.layer is Layer.CreateEvent }
            onNodeWithContentDescription(str(ComponentRes.string.menu)).performClick()
            awaitState(rig) { it.overlays.menuOpen }
            onNodeWithText(str(Res.string.report_problem)).performClick()
            awaitState(rig) { it.overlays.reportingBug && !it.overlays.menuOpen }
            onNodeWithText(str(Res.string.report_placeholder)).performTextInput("No photos arrive")
            onNodeWithText(str(Res.string.report_send)).performClick()
            awaitFired(rig, "sendDiagnostics:No photos arrive")
            awaitState(rig) { it.overlays.reportNotice == app.snapsync.model.ReportOutcome.SENT }
            onNodeWithText(str(Res.string.report_sent)).performClick()
            awaitState(rig) { it.overlays.reportNotice == null }
        }

    @Test
    fun `the menu's links open their pages through the container`() = rigTest(rig(config = MEMBERSHIP)) { rig ->
        awaitState(rig) { it.joined != null }
        onNodeWithContentDescription(str(ComponentRes.string.menu)).performClick()
        awaitState(rig) { it.overlays.menuOpen }
        onNodeWithText(str(Res.string.menu_privacy)).performClick()
        awaitFired(rig, "openLink:${app.snapsync.model.AppLink.PRIVACY_POLICY.url}")
        awaitState(rig) { !it.overlays.menuOpen }
        onNodeWithContentDescription(str(ComponentRes.string.menu)).performClick()
        awaitState(rig) { it.overlays.menuOpen }
        onNodeWithText(str(Res.string.menu_website)).performClick()
        awaitFired(rig, "openLink:${app.snapsync.model.AppLink.WEBSITE.url}")
    }

    @Test
    fun `the menu's mobile-data switch reaches the command and leaves the menu open`() = rigTest(rig()) { rig ->
        // Capability `mobile-data`, with no event: the choice is the device's.
        awaitState(rig) { it.layer is Layer.CreateEvent }
        onNodeWithContentDescription(str(ComponentRes.string.menu)).performClick()
        awaitState(rig) { it.overlays.menuOpen }
        onNodeWithText(str(Res.string.mobile_data_toggle)).performClick()
        awaitFired(rig, "setMobileData:false")
        awaitState(rig) { it.overlays.menuOpen }
    }

    // ---- a refused phone (capabilities `create-event`, `privacy-security`) ----

    @Test
    fun `a refused phone is told why with Create still offered - and Report this opens the sheet already written`() =
        rigTest(rig(diagnostics = true, deviceRefusal = app.snapsync.model.DeviceRefusal.DEVICE_UNVERIFIABLE)) { rig ->
            awaitState(rig) { (it.layer as? Layer.CreateEvent)?.error == ScreenMessage.DEVICE_UNVERIFIABLE }
            val told = "${str(Res.string.message_device_unverifiable)} ${str(Res.string.message_report_this)}"
            onNodeWithText(told).assertExists()
            onNodeWithText(str(Res.string.create_join_hint)).assertDoesNotExist()
            onNodeWithText(str(Res.string.create_button)).assertExists()

            onNodeWithText(told).performClick()
            awaitState(rig) { it.overlays.reportingBug && it.overlays.reportSeed == ScreenMessage.DEVICE_UNVERIFIABLE }
            // Cancelling sends nothing.
            onNodeWithText(str(Res.string.cancel)).performClick()
            awaitState(rig) { !it.overlays.reportingBug && it.overlays.reportSeed == null }
            assertTrue(rig.fired.none { it.startsWith("sendDiagnostics") })

            // Sent as the app wrote it — the user may change it, but need not.
            onNodeWithText(told).performClick()
            awaitState(rig) { it.overlays.reportingBug }
            onNodeWithText(str(Res.string.report_send)).performClick()
            awaitFired(rig, "sendDiagnostics:${str(Res.string.report_seed_device_unverifiable)} [verification]")
        }

    @Test
    fun `a refusal that only a store install can fix offers no report`() =
        rigTest(rig(deviceRefusal = app.snapsync.model.DeviceRefusal.APP_NOT_GENUINE)) { rig ->
            awaitState(rig) { (it.layer as? Layer.CreateEvent)?.error == ScreenMessage.APP_NOT_GENUINE }
            onNodeWithText(str(Res.string.message_app_not_genuine)).assertExists()
            onNodeWithText(str(Res.string.message_report_this), substring = true).assertDoesNotExist()
        }

    // ---- the hidden bug report (capability `privacy-security`) ----

    @Test
    fun `the double-tap opens the report sheet — Cancel closes it — and Send sends`() =
        rigTest(rig(diagnostics = true)) { rig ->
            awaitState(rig) { it.layer is Layer.CreateEvent }
            onNodeWithText("SNAPSYNC").performTouchInput { doubleClick() }
            awaitState(rig) { it.overlays.reportingBug }
            onNodeWithText(str(Res.string.cancel)).performClick()
            awaitState(rig) { !it.overlays.reportingBug }

            onNodeWithText("SNAPSYNC").performTouchInput { doubleClick() }
            awaitState(rig) { it.overlays.reportingBug }
            onNodeWithText(str(Res.string.report_placeholder)).performTextInput("It froze")
            onNodeWithText(str(Res.string.report_send)).performClick()
            awaitFired(rig, "sendDiagnostics:It froze")
            awaitState(rig) { !it.overlays.reportingBug }
        }
}
