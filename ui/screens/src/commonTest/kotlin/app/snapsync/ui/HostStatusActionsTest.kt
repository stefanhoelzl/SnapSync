@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventNameVerdict
import app.snapsync.domain.event.EventRange
import app.snapsync.domain.event.EventRangeVerdict
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.format.InviteLinkText
import app.snapsync.format.TimestampText
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.NetworkAccess
import app.snapsync.model.UserCommands
import app.snapsync.presentation.url
import app.snapsync.query.membership.JoinDetails
import app.snapsync.testids.testEventId
import app.snapsync.ui.components.resources.menu
import app.snapsync.ui.components.resources.network_blocked
import app.snapsync.ui.components.resources.share_range_custom
import app.snapsync.ui.components.resources.share_range_event_start
import app.snapsync.ui.components.resources.share_range_from_now
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
import app.snapsync.ui.resources.store_open
import app.snapsync.ui.resources.switch_confirm
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.ReportNotice
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import app.snapsync.ui.components.resources.Res as ComponentRes

/** The event the joined tests are members of. */
private const val JOINED_ID = "11111111-1111-4111-8111-111111111111"

/** A second event, for the join gate and the switch. */
private const val OTHER_ID = "22222222-2222-4222-8222-222222222222"

private const val STORE_URL = RIG_STORE_URL

private val MEMBERSHIP = EventConfig(
    eventId = testEventId(JOINED_ID),
    name = "Anna's Birthday",
    minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-04T18:00:00Z")),
    startsAt = EventStart(TimestampText.parse("2026-07-04T18:00:00Z")),
    endsAt = EventEnd(TimestampText.parse("2026-07-20T18:00:00Z")),
    maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-20T18:00:00Z")),
    deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
)

/** What the details load answers for [OTHER_ID]: an event that has started and runs past "now". */
private val OTHER_EVENT = JoinDetails.Found(
    Event(
        id = testEventId(OTHER_ID),
        name = (EventName.of("Anna's Wedding") as EventNameVerdict.Ok).name,
        range = (
            EventRange.of(
                EventStart(TimestampText.parse("2026-07-04T18:00:00Z")),
                EventEnd(TimestampText.parse("2026-07-20T18:00:00Z")),
            ) as EventRangeVerdict.Ok
            ).range,
        openUntil = null,
        deletesAt = DeletesAt(TimestampText.parse("2026-08-03T18:00:00Z")),
        encryption = Encryption.Plain,
        members = null,
    ),
    completed = false,
)

private fun linkTo(eventId: String) = InviteLinkText.format(InviteLink(testEventId(eventId), null))

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

    private fun rig(
        config: EventConfig? = null,
        permission: GalleryAccess = GalleryAccess.GRANTED,
        refusal: VersionRefusal? = null,
        @Suppress("UNUSED_PARAMETER") diagnostics: Boolean = false,
        details: suspend (String) -> JoinDetails = { OTHER_EVENT },
        network: NetworkAccess = NetworkAccess.Online(restricted = false),
        deviceRefusal: app.snapsync.domain.trust.DeviceRefusal? = null,
    ) = ContainerRig(config, permission, refusal, details, network, deviceRefusal)

    private val ready: (UiState) -> Boolean =
        { (it.joining?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready }

    private val UiState.joined get() = layer as? Layer.Joined
    private val UiState.joining get() = layer as? Layer.JoiningEvent

    // ---- the update-required store button (capability `app-update-required`) — the binding that had drifted ----

    @Test
    fun `the store button opens the store link through the container`() =
        rigTest(rig(refusal = VersionRefusal("0.4"))) { rig ->
            awaitState(rig) { it.layer is Layer.UpdateRequired }
            onNodeWithText(str(Res.string.store_open, "App Store")).performClick()
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
        completeForm(rig, "My Party")
        onNodeWithText(str(Res.string.create_button)).performClick()
        awaitFired(rig, "create:My Party")
    }

    @Test
    fun `a range already over asks first — a choice creates it open that long`() = rigTest(rig()) { rig ->
        awaitState(rig) { it.layer is Layer.CreateEvent }
        completeForm(rig, "My Party")
        rig.clock.instant = Instant.parse("2026-07-09T12:00:00Z") // the 8th is over
        onNodeWithText(str(Res.string.create_button)).performClick()
        awaitState(rig) { it.create?.form?.keepOpen != null }
        onNode(hasText("7 days", substring = true)).performClick()
        awaitFired(rig, "create:My Party:open-until:2026-07-16T12:00:00Z")
        awaitState(rig) { it.create?.form?.keepOpen == null }
    }

    @Test
    fun `the back-dated sheet's Cancel keeps the form and creates nothing`() = rigTest(rig()) { rig ->
        awaitState(rig) { it.layer is Layer.CreateEvent }
        completeForm(rig, "My Party")
        rig.clock.instant = Instant.parse("2026-07-09T12:00:00Z")
        onNodeWithText(str(Res.string.create_button)).performClick()
        awaitState(rig) { it.create?.form?.keepOpen != null }
        onNodeWithText(str(Res.string.cancel)).performClick()
        awaitState(rig) { it.create?.form?.let { f -> f.keepOpen == null && f.name == "My Party" } == true }
        assertTrue(rig.fired.none { it.startsWith("create:") }, "nothing was created: ${rig.fired}")
    }

    // ---- the join gate (capability `join-event`) ----

    @Test
    fun `an opened link’s gate edits its form and Join commits the choice`() = rigTest(rig()) { rig ->
        rig.host.onOpenUrl(linkTo(OTHER_ID))
        awaitState(rig, ready)

        // Each range edit, so a preset crossed with a custom pick lands in the wrong field. The quick choices are on
        // the screen; Custom opens the calendar.
        onNodeWithText(str(ComponentRes.string.share_range_from_now)).performClick()
        awaitState(rig) { it.joining?.range?.preset == RangeChoice.FROM_NOW }
        onNodeWithText(str(ComponentRes.string.share_range_custom)).performClick()
        awaitState(rig) { it.joining?.range?.picker != null }
        onNodeWithText(str(Res.string.save)).performClick()
        awaitState(rig) {
            it.joining?.range?.preset == RangeChoice.CUSTOM
        }
        onNodeWithText(str(ComponentRes.string.share_range_event_start)).performClick()
        awaitState(rig) { it.joining?.range?.preset == RangeChoice.EVENT_START }
        // The participation switches, likewise one field each.
        onNodeWithText(str(Res.string.share_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.range?.shareOn == false }
        onNodeWithText(str(Res.string.share_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.range?.shareOn == true }
        onNodeWithText(str(Res.string.receive_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.range?.receiveOn == false }
        onNodeWithText(str(Res.string.receive_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.range?.receiveOn == true }
        // The album opt-in defaults ON, so the tap turns it off.
        onNodeWithText(str(Res.string.album_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.range?.saveToAlbum == false }
        // Share off with receive on: a direction only the two switches together can produce.
        onNodeWithText(str(Res.string.share_toggle)).performScrollTo().performClick()
        awaitState(rig) { it.joining?.range?.shareOn == false }

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
        rigTest(
            rig(details = { if (++loads == 1) JoinDetails.Unanswered(ServiceFault.UNREACHABLE) else OTHER_EVENT }),
        ) { rig ->
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
            awaitState(rig) { it.overlays.reportNotice == ReportNotice.SENT }
            onNodeWithText(str(Res.string.report_sent)).performClick()
            awaitState(rig) { it.overlays.reportNotice == null }
        }

    @Test
    fun `the menu's links open their pages through the container`() = rigTest(rig(config = MEMBERSHIP)) { rig ->
        awaitState(rig) { it.joined != null }
        onNodeWithContentDescription(str(ComponentRes.string.menu)).performClick()
        awaitState(rig) { it.overlays.menuOpen }
        onNodeWithText(str(Res.string.menu_privacy)).performClick()
        awaitFired(rig, "openLink:${app.snapsync.ui.state.AppLink.PRIVACY_POLICY.url}")
        awaitState(rig) { !it.overlays.menuOpen }
        onNodeWithContentDescription(str(ComponentRes.string.menu)).performClick()
        awaitState(rig) { it.overlays.menuOpen }
        onNodeWithText(str(Res.string.menu_website)).performClick()
        awaitFired(rig, "openLink:${app.snapsync.ui.state.AppLink.WEBSITE.url}")
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
        rigTest(
            rig(diagnostics = true, deviceRefusal = app.snapsync.domain.trust.DeviceRefusal.DEVICE_UNVERIFIABLE),
        ) { rig ->
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
        rigTest(rig(deviceRefusal = app.snapsync.domain.trust.DeviceRefusal.APP_NOT_GENUINE)) { rig ->
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
