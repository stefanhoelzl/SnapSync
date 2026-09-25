@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import app.snapsync.presentation.StatusDiagnostics
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import app.snapsync.feature.status.SyncStatusSource
import app.snapsync.feature.version.AppVersionGate
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.FromChoice
import app.snapsync.model.JoinCommit
import app.snapsync.model.JoinLoad
import app.snapsync.model.PermissionStatus
import app.snapsync.model.SyncStatus
import app.snapsync.model.UntilChoice
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.UserCommands
import app.snapsync.model.ReconfigureOutcome
import app.snapsync.model.UserQueries
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.model.JoinPhase
import app.snapsync.model.JoinedSurface
import app.snapsync.model.Layer
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusSources
import app.snapsync.model.UiState
import app.snapsync.ui.components.LocalReduceMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.TimeZone

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
        permission: PermissionStatus = PermissionStatus.GRANTED,
        refusal: AppVersionGate.Refusal? = null,
        diagnostics: Boolean = false,
        private val details: suspend (String) -> JoinLoad = { OTHER_EVENT },
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
                appStoreUrl = STORE_URL,
            ),
            scope = scope,
            cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-06T12:00:00Z") }, zone = TimeZone.UTC),
            commands = UserCommands(
                leave = {
                    record("leave")
                    this.config.value = null
                },
                create = { name, _, _ -> record("create:$name") },
                commitJoin = { eventId, _, _, _, _, _, _, direction, album ->
                    record("commitJoin:$eventId:$direction:$album")
                    JoinCommit.Failed
                },
                share = { record("share:$it") },
                requestAccess = { record("requestAccess") },
                openSettings = { record("openSettings") },
                openLink = { record("openLink:$it") },
                choosePhotos = { record("choosePhotos") },
                reconfigure = { eventId, _, _, _, _ -> record("reconfigure:$eventId"); ReconfigureOutcome.Saved },
                rename = { eventId, name -> record("rename:$eventId:$name") },
                resetRename = { record("resetRename") },
                sendDiagnostics = if (diagnostics) ({ note, _ -> record("sendDiagnostics:$note") }) else null,
            ),
            queries = UserQueries(loadJoinDetails = { details(it) }, shareableCount = { _, _ -> null }),
            diagnostics = StatusDiagnostics(log = {}, onIntentError = {}),
        )

        val state: UiState get() = host.container.stateFlow.value
    }

    /** Compose [rig]'s real screen with the shared factory's bundle, as every host does. */
    private fun ComposeUiTest.show(rig: Rig) {
        setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                val state by rig.host.container.stateFlow.collectAsState()
                StatusScreen(
                    state = state,
                    cutoff = CutoffFormatter(now = { Instant.parse("2026-07-06T12:00:00Z") }, zone = TimeZone.UTC),
                    actions = statusActions(rig.host),
                )
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
        permission: PermissionStatus = PermissionStatus.GRANTED,
        refusal: AppVersionGate.Refusal? = null,
        diagnostics: Boolean = false,
        details: suspend (String) -> JoinLoad = { OTHER_EVENT },
    ) = Rig(config, permission, refusal, diagnostics, details)

    private val ready: (UiState) -> Boolean =
        { (it.joining?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready }

    private val UiState.joined get() = layer as? Layer.Joined
    private val UiState.joining get() = layer as? Layer.JoiningEvent

    // ---- the update-required store button (capability `app-update-required`) — the binding that had drifted ----

    @Test
    fun `the store button opens the store link through the container`() =
        rigTest(rig(refusal = AppVersionGate.Refusal("0.4"))) { rig ->
            awaitState(rig) { it.layer is Layer.UpdateRequired }
            onNodeWithText("Open the App Store").performClick()
            awaitFired(rig, "openLink:$STORE_URL")
        }

    // ---- the joined layer ----

    @Test
    fun `leave opens its confirmation — Stay dismisses it — and Leave fires the leave`() =
        rigTest(rig(config = MEMBERSHIP)) { rig ->
            awaitState(rig) { it.joined != null }
            onNodeWithContentDescription("Leave event").performClick()
            awaitState(rig) { it.overlays.confirmingLeave }
            onNodeWithText("Stay").performClick()
            awaitState(rig) { !it.overlays.confirmingLeave }
            assertEquals(emptyList(), rig.fired)

            onNodeWithContentDescription("Leave event").performClick()
            awaitState(rig) { it.overlays.confirmingLeave }
            onNodeWithText("Leave").performClick()
            awaitFired(rig, "leave")
        }

    @Test
    fun `share hands the rendered invite link to the platform share`() =
        rigTest(rig(config = MEMBERSHIP)) { rig ->
            awaitState(rig) { it.joined != null }
            val invite = rig.state.joined!!.inviteUrl
            onNodeWithContentDescription("Share invite link").performClick()
            awaitFired(rig, "share:$invite")
        }

    @Test
    fun `the pen opens the rename sheet — Cancel dismisses it — and Save renames`() =
        rigTest(rig(config = MEMBERSHIP)) { rig ->
            awaitState(rig) { it.joined != null }
            onNodeWithContentDescription("Rename event").performClick()
            awaitState(rig) { it.overlays.renaming }
            onNodeWithText("Cancel").performClick()
            awaitState(rig) { !it.overlays.renaming }
            // Dismissing also clears the rename latch.
            awaitFired(rig, "resetRename")

            onNodeWithContentDescription("Rename event").performClick()
            awaitState(rig) { it.overlays.renaming }
            onNode(hasSetTextAction()).performTextClearance()
            onNode(hasSetTextAction()).performTextInput("Anna's Party")
            onNodeWithText("Save").performClick()
            awaitFired(rig, "rename:$JOINED_ID:Anna's Party")
        }

    @Test
    fun `the gear opens the settings surface — Cancel closes it — and Save reconfigures`() =
        rigTest(rig(config = MEMBERSHIP)) { rig ->
            awaitState(rig) { it.joined != null }
            onNodeWithContentDescription("Event settings").performClick()
            awaitState(rig) { it.joined?.surface is JoinedSurface.Reconfigure }
            onNodeWithText("Cancel").performClick()
            awaitState(rig) { it.joined != null && it.joined?.surface !is JoinedSurface.Reconfigure }
            assertEquals(emptyList(), rig.fired)

            onNodeWithContentDescription("Event settings").performClick()
            awaitState(rig) { it.joined?.surface is JoinedSurface.Reconfigure }
            onNodeWithText("Save").performClick()
            awaitFired(rig, "reconfigure:$JOINED_ID")
        }

    // ---- the access prompts (capabilities `photo-access`, `photo-access`) ----

    @Test
    fun `a never-asked grant's prompt requests access`() =
        rigTest(rig(config = MEMBERSHIP, permission = PermissionStatus.NOT_DETERMINED)) { rig ->
            awaitState(rig) { it.joined != null }
            onNodeWithText("Allow photo access").performClick()
            awaitFired(rig, "requestAccess")
            assertEquals(listOf("requestAccess"), rig.fired)
        }

    @Test
    fun `a denied grant's prompt opens Settings`() =
        rigTest(rig(config = MEMBERSHIP, permission = PermissionStatus.DENIED)) { rig ->
            awaitState(rig) { it.joined != null }
            onNodeWithText("Turn on full access in Settings").performClick()
            awaitFired(rig, "openSettings")
            assertEquals(listOf("openSettings"), rig.fired)
        }

    @Test
    fun `a partial grant offers the picker and Settings — each its own`() =
        rigTest(rig(config = MEMBERSHIP, permission = PermissionStatus.LIMITED)) { rig ->
            awaitState(rig) { it.joined?.canChoosePhotos == true }
            onNodeWithText("Choose more photos").performClick()
            awaitFired(rig, "choosePhotos")
            assertEquals(listOf("choosePhotos"), rig.fired)
            onNodeWithText("Allow full access").performClick()
            awaitFired(rig, "openSettings")
        }

    // ---- the create layer ----

    @Test
    fun `create submits the typed name`() = rigTest(rig()) { rig ->
        awaitState(rig) { it.layer is Layer.CreateEvent }
        onNode(hasSetTextAction()).performTextInput("My Party")
        onNodeWithText("Create event").performClick()
        awaitFired(rig, "create:My Party")
    }

    // ---- the join gate (capability `join-event`) ----

    @Test
    fun `an opened link's gate edits its form and Join commits the choice`() = rigTest(rig()) { rig ->
        rig.host.onOpenUrl(linkTo(OTHER_ID))
        awaitState(rig, ready)

        // Each range handle, so a From binding crossed with an Until one — or a preset with a custom pick —
        // lands in the wrong field.
        onNodeWithTag("from-now").performClick()
        awaitState(rig) { it.joining?.form?.fromPreset == FromChoice.NOW }
        onNodeWithTag("from-custom").performClick()
        onNodeWithText("OK").performClick()
        awaitState(rig) { it.joining?.form?.let { f -> f.fromPreset == FromChoice.CUSTOM && f.fromCustom != null } == true }
        onNodeWithTag("until-custom").performClick()
        onNodeWithText("OK").performClick()
        awaitState(rig) { it.joining?.form?.let { f -> f.untilPreset == UntilChoice.CUSTOM && f.untilCustom != null } == true }
        onNodeWithTag("until-event-end").performClick()
        awaitState(rig) { it.joining?.form?.untilPreset == UntilChoice.EVENT_END }
        // The participation switches, likewise one field each.
        onNodeWithText("Share my photos").performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.shareOn == false }
        onNodeWithText("Share my photos").performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.shareOn == true }
        onNodeWithText("Receive everyone's photos").performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.receiveOn == false }
        onNodeWithText("Receive everyone's photos").performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.receiveOn == true }
        // The album opt-in defaults ON, so the tap turns it off.
        onNodeWithText("Create an album").performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.saveToAlbum == false }
        // Share off with receive on: a direction only the two switches together can produce.
        onNodeWithText("Share my photos").performScrollTo().performClick()
        awaitState(rig) { it.joining?.form?.shareOn == false }

        onNodeWithText("Join").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.fired.any { it.startsWith("commitJoin:") } }
        assertEquals(listOf("commitJoin:$OTHER_ID:DownloadOnly:false"), rig.fired)
    }

    @Test
    fun `Cancel discards the pending join without committing`() = rigTest(rig()) { rig ->
        rig.host.onOpenUrl(linkTo(OTHER_ID))
        awaitState(rig, ready)
        onNodeWithText("Cancel").performClick()
        awaitState(rig) { it.layer is Layer.CreateEvent }
        assertEquals(emptyList(), rig.fired)
    }

    @Test
    fun `the access explainer requests access and advances to the confirm`() =
        rigTest(rig(permission = PermissionStatus.NOT_DETERMINED)) { rig ->
            rig.host.onOpenUrl(linkTo(OTHER_ID))
            awaitState(rig) { (it.joining?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.ExplainAccess }
            onNodeWithText("I understand").performClick()
            awaitState(rig, ready)
            assertEquals(listOf("requestAccess"), rig.fired)
        }

    @Test
    fun `Retry after a failed load loads again`() {
        var loads = 0
        rigTest(rig(details = { if (++loads == 1) JoinLoad.Failed else OTHER_EVENT })) { rig ->
            rig.host.onOpenUrl(linkTo(OTHER_ID))
            awaitState(rig) { it.joining?.phase == JoinPhase.LoadFailed }
            onNodeWithText("Retry").performClick()
            awaitState(rig, ready)
            assertEquals(2, loads)
        }
    }

    @Test
    fun `Retry after a failed commit commits again`() = rigTest(rig()) { rig ->
        rig.host.onOpenUrl(linkTo(OTHER_ID))
        awaitState(rig, ready)
        onNodeWithText("Join").performClick()
        awaitState(rig) { (it.joining?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.CommitFailed }
        onNodeWithText("Retry").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.fired.count { it.startsWith("commitJoin:") } == 2 }
    }

    // ---- the switch confirmation (a different event scanned while joined) ----

    @Test
    fun `the switch confirmation's Cancel keeps the membership and Switch leaves it`() =
        rigTest(rig(config = MEMBERSHIP)) { rig ->
            rig.host.onOpenUrl(linkTo(OTHER_ID))
            awaitState(rig) { it.joined?.pendingSwitch != null }
            onNodeWithText("Cancel").performClick()
            awaitState(rig) { it.joined != null && it.joined?.pendingSwitch == null }
            assertEquals(emptyList(), rig.fired)

            rig.host.onOpenUrl(linkTo(OTHER_ID))
            awaitState(rig) { it.joined?.pendingSwitch != null }
            onNodeWithText("Switch").performClick()
            awaitFired(rig, "leave")
            // The leave cleared the membership, so the gate re-renders as the new event's own join surface.
            awaitState(rig) { it.joining?.eventId == OTHER_ID }
        }

    // ---- the hidden bug report (capability `privacy-security`) ----

    @Test
    fun `the double-tap opens the report sheet — Cancel closes it — and Send sends`() =
        rigTest(rig(diagnostics = true)) { rig ->
            awaitState(rig) { it.layer is Layer.CreateEvent }
            onNodeWithText("SNAPSYNC").performTouchInput { doubleClick() }
            awaitState(rig) { it.overlays.reportingBug }
            onNodeWithText("Cancel").performClick()
            awaitState(rig) { !it.overlays.reportingBug }

            onNodeWithText("SNAPSYNC").performTouchInput { doubleClick() }
            awaitState(rig) { it.overlays.reportingBug }
            onNodeWithText("What went wrong, and what were you doing?").performTextInput("It froze")
            onNodeWithText("Send").performClick()
            awaitFired(rig, "sendDiagnostics:It froze")
            awaitState(rig) { !it.overlays.reportingBug }
        }
}
