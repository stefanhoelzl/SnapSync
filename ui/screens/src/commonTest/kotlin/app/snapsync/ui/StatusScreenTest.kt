@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.snapsync.ui.components.LocalReduceMotion
import app.snapsync.ui.components.resources.cannot_verify_detail
import app.snapsync.ui.components.resources.cannot_verify_title
import app.snapsync.ui.components.resources.cannot_verify_title_modified
import app.snapsync.ui.components.resources.cannot_verify_title_not_genuine
import app.snapsync.ui.components.resources.cannot_verify_title_unverifiable
import app.snapsync.ui.components.resources.date_range_today
import app.snapsync.ui.components.resources.key_lost_detail
import app.snapsync.ui.components.resources.key_lost_title
import app.snapsync.ui.components.resources.network_blocked
import app.snapsync.ui.components.resources.network_offline
import app.snapsync.ui.components.resources.range_starts
import app.snapsync.ui.components.resources.status_allow_access
import app.snapsync.ui.components.resources.status_allow_access_settings
import app.snapsync.ui.components.resources.status_in_sync
import app.snapsync.ui.components.resources.status_inactive
import app.snapsync.ui.components.resources.status_not_started
import app.snapsync.ui.components.resources.status_sync_ongoing
import app.snapsync.ui.components.resources.status_sync_pending
import app.snapsync.ui.components.resources.status_waiting_wifi
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.album_toggle
import app.snapsync.ui.resources.allow_full_access
import app.snapsync.ui.resources.cancel
import app.snapsync.ui.resources.choose_more_photos
import app.snapsync.ui.resources.counts_line
import app.snapsync.ui.resources.counts_line_members
import app.snapsync.ui.resources.counts_not_receiving
import app.snapsync.ui.resources.counts_not_sharing
import app.snapsync.ui.resources.counts_received
import app.snapsync.ui.resources.counts_received_progress
import app.snapsync.ui.resources.counts_shared
import app.snapsync.ui.resources.counts_shared_progress
import app.snapsync.ui.resources.create_name_placeholder
import app.snapsync.ui.resources.create_title
import app.snapsync.ui.resources.creating
import app.snapsync.ui.resources.duration_days
import app.snapsync.ui.resources.duration_hours
import app.snapsync.ui.resources.duration_minutes_short
import app.snapsync.ui.resources.event_over_body
import app.snapsync.ui.resources.event_over_title
import app.snapsync.ui.resources.everyone_shared
import app.snapsync.ui.resources.explain_access_link
import app.snapsync.ui.resources.explain_hint_caption
import app.snapsync.ui.resources.explain_hint_title
import app.snapsync.ui.resources.explain_receive_album
import app.snapsync.ui.resources.explain_receive_blocked_caption
import app.snapsync.ui.resources.explain_receive_blocked_title
import app.snapsync.ui.resources.explain_receive_no_album
import app.snapsync.ui.resources.explain_receive_off_caption
import app.snapsync.ui.resources.explain_receive_off_title
import app.snapsync.ui.resources.explain_receive_title
import app.snapsync.ui.resources.explain_settings_link
import app.snapsync.ui.resources.explain_share_blocked_caption
import app.snapsync.ui.resources.explain_share_blocked_title
import app.snapsync.ui.resources.explain_share_caption
import app.snapsync.ui.resources.explain_share_limited_caption
import app.snapsync.ui.resources.explain_share_limited_title
import app.snapsync.ui.resources.explain_share_off_caption
import app.snapsync.ui.resources.explain_share_off_title
import app.snapsync.ui.resources.explain_share_title
import app.snapsync.ui.resources.footer_settings
import app.snapsync.ui.resources.invite_caption
import app.snapsync.ui.resources.joinable_until
import app.snapsync.ui.resources.joined_statement
import app.snapsync.ui.resources.leave_cancel
import app.snapsync.ui.resources.leave_confirm
import app.snapsync.ui.resources.leave_event
import app.snapsync.ui.resources.leave_title
import app.snapsync.ui.resources.members_count
import app.snapsync.ui.resources.message_device_unverifiable
import app.snapsync.ui.resources.message_invalid_link
import app.snapsync.ui.resources.message_rename_failed
import app.snapsync.ui.resources.message_rename_name_refused
import app.snapsync.ui.resources.message_report_this
import app.snapsync.ui.resources.mobile_data_toggle
import app.snapsync.ui.resources.qr_sheet_title
import app.snapsync.ui.resources.range_custom
import app.snapsync.ui.resources.range_whole_event
import app.snapsync.ui.resources.receive_toggle
import app.snapsync.ui.resources.rename_body
import app.snapsync.ui.resources.rename_event
import app.snapsync.ui.resources.save
import app.snapsync.ui.resources.settings_save_failed
import app.snapsync.ui.resources.share_invite
import app.snapsync.ui.resources.share_toggle
import app.snapsync.ui.resources.show_qr
import app.snapsync.ui.resources.stop_sharing_body
import app.snapsync.ui.resources.stop_sharing_confirm
import app.snapsync.ui.resources.stop_sharing_keep
import app.snapsync.ui.resources.stop_sharing_title
import app.snapsync.ui.resources.store_app_store
import app.snapsync.ui.resources.store_google_play
import app.snapsync.ui.resources.timing_ended
import app.snapsync.ui.resources.timing_ends_in
import app.snapsync.ui.resources.timing_starts_in
import app.snapsync.ui.resources.update_detail
import app.snapsync.ui.resources.update_detail_version
import app.snapsync.ui.resources.update_eyebrow
import app.snapsync.ui.resources.waiting_members
import app.snapsync.ui.state.AlbumStyle
import app.snapsync.ui.state.Arrow
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.EventTiming
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinedEvent
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.Overlays
import app.snapsync.ui.state.PendingSwitch
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.RefusalCause
import app.snapsync.ui.state.RenameState
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.SharingNote
import app.snapsync.ui.state.StoreName
import app.snapsync.ui.state.SyncCounts
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.TimeLeft
import app.snapsync.ui.state.UiState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.snapsync.ui.components.resources.Res as ComponentRes

// A representative invite link — any string renders a QR; the encoding is pinned in capability:config.
private const val SAMPLE_INVITE = "https://snapsync.stho.net/join#v=3&d=eyJldmVudElkIjoiMSJ9"

// A representative joined membership for the reconfigure-surface tests: event started, the full window
// `[startsAt, endsAt]` at both bounds (floor + ceiling), bidirectional, no album — so a no-edit Save
// round-trips these exact values.
private val MEMBERSHIP = joinedEvent(
    eventId = "E1",
    name = "Anna's Birthday",
    startsAt = LocalDateTime(2026, 7, 6, 12, 0),
    endsAt = LocalDateTime(2026, 7, 10, 12, 0),
)

/** The fixed test clock's date (UTC). */
private val TODAY = LocalDate(2026, 7, 6)

/** The joined layer with the leave confirmation up — the state the leave action produces. */
private fun confirmingLeave() = inSync.copy(overlays = Overlays(confirmingLeave = true))

/** The joined layer with the rename sheet up, optionally carrying a rename outcome. */
private fun renaming(renameState: RenameState = RenameState.Idle) =
    joined(SyncHealth.InSync, renameState = renameState).copy(overlays = Overlays(renaming = true))

/**
 * The joined layer showing its SETTINGS surface, with [form] already seeded and resolved.
 *
 * Opening the surface is an intent now, so a screen test cannot tap its way in — it supplies the state
 * the tap produces and asserts what is drawn. Whether the seed RECONSTRUCTS the right presets from a
 * persisted membership is `reconfigureForm`'s answer, checked in `RangeResolutionTest`.
 */
private fun reconfiguring(
    membership: JoinedEvent = MEMBERSHIP,
    form: Choices = Choices(saveToAlbum = membership.savesToAlbum),
    saveFailed: Boolean = false,
    askingToStopSharing: Boolean = false,
) = UiState(
    Layer.Joined(
        membership = membership,
        inviteUrl = SAMPLE_INVITE,
        health = SyncHealth.InSync,
        today = TODAY,
        surface = JoinedSurface.Reconfigure(
            form.over(
                membership.startsAt,
                membership.endsAt,
                now = LocalDateTime(2026, 7, 6, 12, 0),
                today = membership.startsAt.date,
            ),
            saveFailed,
            askingToStopSharing,
        ),
    ),
)

private fun joined(
    health: SyncHealth,
    pendingSwitch: PendingSwitch? = null,
    canChoosePhotos: Boolean = false,
    timing: EventTiming = EventTiming.Running(TimeLeft.Days(4)),
    counts: SyncCounts? = null,
    membership: JoinedEvent = MEMBERSHIP,
    inviteUrl: String? = SAMPLE_INVITE,
    renameState: RenameState = RenameState.Idle,
    deleted: Boolean = false,
    members: Int? = null,
    sharingNote: SharingNote? = null,
) = UiState(
    Layer.Joined(
        membership = membership,
        inviteUrl = inviteUrl,
        health = health,
        today = TODAY,
        pendingSwitch = pendingSwitch,
        canChoosePhotos = canChoosePhotos,
        timing = timing,
        counts = counts,
        renameState = renameState,
        deleted = deleted,
        members = members,
        sharingNote = sharingNote,
    ),
)

private fun progress(done: Int, total: Int) = DirectionCount.Progress(done, total)
private val inSync = joined(SyncHealth.InSync)

/** [MEMBERSHIP]'s shared range, as the explanation words it. */
private const val RANGE = "Mon 6 Jul – Fri 10 Jul"

private val SE2_WIDTH = 375.dp
private val SE2_HEIGHT = 667.dp

/** A caption whose `%1$s` holds its [link]'s words, the way the explanation row renders it. */
private fun caption(res: StringResource, link: StringResource, vararg rest: Any): String = str(res, str(link), *rest)

private val COUNT = Regex("\\d+(/\\d+)? (shared|received)")
private val syncing = joined(SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN))
private val syncPending = joined(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN))
private val waitingForWifi = joined(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN, waitingForWifi = true))

class StatusScreenTest {

    @Test
    fun `a refusal on the create screen offers the report in the line below Create`() = runComposeUiTest {
        var reported: ScreenMessage? = null
        setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                TestStatusScreen(
                    UiState(createLayer(error = ScreenMessage.DEVICE_UNVERIFIABLE)),
                    actions = testActions(surfaces = testSurfaceActions(onReportRefusal = { reported = it })),
                )
            }
        }
        onNodeWithText(
            "${str(Res.string.message_device_unverifiable)} ${str(Res.string.message_report_this)}",
        ).performClick()
        assertEquals(ScreenMessage.DEVICE_UNVERIFIABLE, reported)
    }

    // ---- the not-started clock line ----

    // ---- the update-required screen (capability `app-update-required`) ----

    @Test
    fun `the update screen names the minimum and offers the store`() = runComposeUiTest {
        var opened = 0
        setContent {
            TestStatusScreen(
                UiState(
                    Layer.UpdateRequired(minimumVersion = "0.4", store = StoreName.APP_STORE),
                ),
                actions = testActions(onOpenStore = { opened++ }),
            )
        }
        onNodeWithText(str(Res.string.update_eyebrow)).assertExists() // its own verb, not another surface's
        onNodeWithText(str(Res.string.update_detail_version, "0.4")).assertExists()
        onNodeWithText(str(Res.string.store_app_store)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `an unstated minimum is not invented and no url means no button`() = runComposeUiTest {
        // Both absences are answers, and both must LOOK like answers. A composed store URL would be the
        // country-less form, which 404s while availability is limited to one storefront — a button that
        // lands nowhere, on the screen a member reaches because something is already wrong.
        setContent {
            TestStatusScreen(
                UiState(Layer.UpdateRequired(minimumVersion = null, store = null)),
            )
        }
        onNodeWithText(str(Res.string.update_detail)).assertExists()
        onNodeWithText(str(Res.string.store_app_store)).assertDoesNotExist()
        onNodeWithText(str(Res.string.store_google_play)).assertDoesNotExist()
    }

    @Test
    fun `on Android the update screen offers Google Play by name`() = runComposeUiTest {
        var opened = 0
        setContent {
            TestStatusScreen(
                UiState(
                    Layer.UpdateRequired(minimumVersion = "0.4", store = StoreName.GOOGLE_PLAY),
                ),
                actions = testActions(onOpenStore = { opened++ }),
            )
        }
        onNodeWithText(str(Res.string.store_app_store)).assertDoesNotExist()
        onNodeWithText(str(Res.string.store_google_play)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the cannot-verify line names the cause of a refusal and names none without one - never tappable`() = runComposeUiTest {
        // Capability `sync-status`, "A device that cannot be verified is shown, and never blamed on the member".
        val state = mutableStateOf(joined(SyncHealth.Unattested()))
        setContent { TestStatusScreen(state.value) }
        val titles = mapOf(
            null to ComponentRes.string.cannot_verify_title,
            RefusalCause.DEVICE_MODIFIED to ComponentRes.string.cannot_verify_title_modified,
            RefusalCause.DEVICE_UNVERIFIABLE to ComponentRes.string.cannot_verify_title_unverifiable,
            RefusalCause.APP_NOT_GENUINE to ComponentRes.string.cannot_verify_title_not_genuine,
        )
        for ((cause, title) in titles) {
            state.value = joined(SyncHealth.Unattested(cause))
            waitForIdle()
            onNodeWithText(str(title)).assertExists().assertHasNoClickAction()
            onNodeWithText(str(ComponentRes.string.cannot_verify_detail)).assertExists()
        }
    }

    @Test
    fun `before the start the dates say when and the status line says what the wait means`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NotStarted, timing = EventTiming.Upcoming(TimeLeft.Days(2))),
            )
        }
        // Time is said once, on the dates line; the status line no longer restates the start.
        onNodeWithText(
            "Mon 6 Jul – Fri 10 Jul" + " · " + str(Res.string.timing_starts_in, plural(Res.plurals.duration_days, 2, 2)),
        ).assertExists()
        onNodeWithText(str(ComponentRes.string.status_not_started)).assertExists()
        onNodeWithText(str(ComponentRes.string.range_starts), substring = true).assertDoesNotExist()
        // It is information, not an action: no arrows, no "Up to date", and no counts.
        onNodeWithText(str(ComponentRes.string.status_in_sync)).assertDoesNotExist()
        onNodeWithText(str(ComponentRes.string.status_sync_pending)).assertDoesNotExist()
        onNode(countsText()).assertDoesNotExist()
    }

    @Test
    fun `a membership that neither shares nor receives says so without counts`() = runComposeUiTest {
        setContent { TestStatusScreen(joined(SyncHealth.Inactive)) }
        onNodeWithText(str(ComponentRes.string.status_inactive)).assertExists()
        // It replaces "Up to date", and nothing is counted: nothing moves.
        onNodeWithText(str(ComponentRes.string.status_in_sync)).assertDoesNotExist()
        onNode(countsText()).assertDoesNotExist()
    }

    @Test
    fun `a lost key asks for the event's invite and is not tappable`() = runComposeUiTest {
        setContent { TestStatusScreen(joined(SyncHealth.KeyLost)) }
        onNodeWithText(str(ComponentRes.string.key_lost_title)).assertExists().assertHasNoClickAction()
        onNodeWithText(str(ComponentRes.string.key_lost_detail)).assertExists()
        // It replaces "Up to date", and nothing is counted: nothing moves.
        onNodeWithText(str(ComponentRes.string.status_in_sync)).assertDoesNotExist()
        onNode(countsText()).assertDoesNotExist()
    }

    @Test
    fun `an event with no whole invite offers neither share nor QR`() = runComposeUiTest {
        setContent { TestStatusScreen(joined(SyncHealth.KeyLost, inviteUrl = null)) }
        onNodeWithText(str(Res.string.share_invite)).assertDoesNotExist()
        onNodeWithText(str(Res.string.show_qr)).assertDoesNotExist()
        // Settings and Leave stay.
        onNodeWithText(str(Res.string.leave_event)).assertExists()
    }

    // ---- create layer ----
    //
    // Every create test composes with reduce motion: the range picker's wheels would otherwise animate, and
    // an animating scene never idles. The fixed clock reads Monday 6 July 2026, 12:00 UTC.

    @Test
    fun `creating event shows a preparing indicator and no input`() = runComposeUiTest {
        setContent { TestStatusScreen(UiState(Layer.CreatingEvent)) }

        onNodeWithText(str(Res.string.creating)).assertExists()
        onNode(hasAnyProgressIndication()).assertExists()
        onNodeWithText(str(Res.string.create_name_placeholder)).assertDoesNotExist()
    }

    // ---- joined layer: status line ----

    @Test
    fun `in sync shows the settled line and no counts`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync) }

        onNodeWithText(str(ComponentRes.string.status_in_sync)).assertExists()
        onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `syncing with an in-flight arrow reads ongoing`() = runComposeUiTest {
        setContent { TestStatusScreen(syncing) }

        onNodeWithText(str(ComponentRes.string.status_sync_ongoing)).assertExists()
        onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `syncing with a static arrow reads pending`() = runComposeUiTest {
        setContent { TestStatusScreen(syncPending) }

        onNodeWithText(str(ComponentRes.string.status_sync_pending)).assertExists()
        onNodeWithText(str(ComponentRes.string.status_sync_ongoing)).assertDoesNotExist()
    }

    /** Capability `mobile-data`: photos kept off mobile data on a network that choice avoids say why they wait. */
    @Test
    fun `photos held for Wi-Fi read waiting for Wi-Fi`() = runComposeUiTest {
        setContent { TestStatusScreen(waitingForWifi) }

        onNodeWithText(str(ComponentRes.string.status_waiting_wifi)).assertExists()
        onNodeWithText(str(ComponentRes.string.status_sync_pending)).assertDoesNotExist()
    }

    // ---- reduce motion (`docs/architecture.md`) ----

    /**
     * The requirement is an **absence** — "SHALL respect reduced-motion preferences" — so the test asserts
     * one: render two frames a third of a pulse apart and prove the pixels are identical. That is the
     * property itself, not a proxy for it. The control below is what makes it mean anything.
     */
    @Test
    fun `reduce motion leaves the pulsing arrow un-animated`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalReduceMotion provides true,
            ) { TestStatusScreen(syncing) }
        }
        onNodeWithText(str(ComponentRes.string.status_sync_ongoing)).assertExists()

        val first = onRoot().captureToImage().toPixelMap()
        mainClock.advanceTimeBy(350)
        val second = onRoot().captureToImage().toPixelMap()

        assertTrue(samePixels(first, second), "reduce motion must leave the frame unchanged over time")
    }

    /** The control: without the preference the same state DOES move — or the test above proves nothing. */
    @Test
    fun `without reduce motion the pulsing arrow animates`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalReduceMotion provides false,
            ) { TestStatusScreen(syncing) }
        }
        onNodeWithText(str(ComponentRes.string.status_sync_ongoing)).assertExists()

        val first = onRoot().captureToImage().toPixelMap()
        mainClock.advanceTimeBy(350) // half the 700ms fade — the alpha cannot be back where it started
        val second = onRoot().captureToImage().toPixelMap()

        assertFalse(samePixels(first, second), "a pulsing arrow animates when motion is allowed")
    }

    /** Reduce motion changes no meaning: the label still distinguishes in-flight from merely pending. */
    @Test
    fun `reduce motion keeps the ongoing-vs-pending distinction`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalReduceMotion provides true,
            ) { TestStatusScreen(syncPending) }
        }

        onNodeWithText(str(ComponentRes.string.status_sync_pending)).assertExists()
        onNodeWithText(str(ComponentRes.string.status_sync_ongoing)).assertDoesNotExist()
    }

    @Test
    fun `needs-access not-determined shows the allow copy and taps request permission`() = runComposeUiTest {
        var requests = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NeedsAccess(canAsk = true)),
                actions = testActions(
                    access = testAccessActions(
                        onRequestPermission = { requests++ },
                    ),
                ),
            )
        }

        onNodeWithText(str(ComponentRes.string.status_allow_access)).assertExists()
        onNodeWithText(str(ComponentRes.string.status_allow_access)).performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `needs-access denied shows the settings copy and taps open settings`() = runComposeUiTest {
        var settingsOpens = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NeedsAccess(canAsk = false)),
                actions = testActions(
                    access = testAccessActions(
                        onOpenSettings = { settingsOpens++ },
                    ),
                ),
            )
        }

        onNodeWithText(str(ComponentRes.string.status_allow_access_settings)).assertExists()
        onNodeWithText(str(ComponentRes.string.status_allow_access_settings)).performClick()
        assertEquals(1, settingsOpens)
    }

    // ---- no network (capabilities `sync-status`, `create-event`) ----

    @Test
    fun `a blocked network's status line opens Settings`() = runComposeUiTest {
        var settingsOpens = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NoNetwork(NetworkNotice.BLOCKED)),
                actions = testActions(access = testAccessActions(onOpenSettings = { settingsOpens++ })),
            )
        }
        onNodeWithText(str(ComponentRes.string.network_blocked)).performClick()
        assertEquals(1, settingsOpens)
    }

    @Test
    fun `the offline status line offers nothing`() = runComposeUiTest {
        var settingsOpens = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NoNetwork(NetworkNotice.OFFLINE)),
                actions = testActions(access = testAccessActions(onOpenSettings = { settingsOpens++ })),
            )
        }
        onNodeWithText(
            str(ComponentRes.string.network_offline),
        ).assertExists().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        onNodeWithText(str(ComponentRes.string.network_offline)).performClick()
        assertEquals(0, settingsOpens)
    }

    // ---- joined layer: partial-grant resting affordances (capability `photo-access`) ----

    @Test
    fun `limited grant shows both affordances in order — in every health`() = runComposeUiTest {
        // One recomposing scene walks the healths — the affordances are resting offers, present
        // regardless of the current health value, with the grant switch always BELOW the selection
        // widening (the cheaper step leads).
        val state = mutableStateOf<UiState>(joined(SyncHealth.InSync, canChoosePhotos = true))
        setContent { TestStatusScreen(state.value) }

        val healths = listOf(
            SyncHealth.InSync,
            SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN),
            SyncHealth.NotStarted,
        )
        for (health in healths) {
            state.value = joined(health, canChoosePhotos = true)
            waitForIdle()
            val chooseY = onNodeWithText(str(Res.string.choose_more_photos)).fetchSemanticsNode().positionInRoot.y
            val allowY = onNodeWithText(str(Res.string.allow_full_access)).fetchSemanticsNode().positionInRoot.y
            assertTrue(allowY > chooseY, "Allow full access must sit below Choose more photos ($health)")
        }
    }

    @Test
    fun `allow full access taps open settings and nothing else`() = runComposeUiTest {
        var settingsOpens = 0
        var pickerOpens = 0
        var requests = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.InSync, canChoosePhotos = true),
                actions = testActions(
                    access = testAccessActions(
                        onOpenSettings = { settingsOpens++ },
                        onChoosePhotos = { pickerOpens++ },
                        onRequestPermission = { requests++ },
                    ),
                ),
            )
        }

        onNodeWithText(str(Res.string.allow_full_access)).performClick()
        assertEquals(1, settingsOpens)
        assertEquals(0, pickerOpens)
        assertEquals(0, requests)
    }

    @Test
    fun `choose more photos taps the picker callback and not settings`() = runComposeUiTest {
        var settingsOpens = 0
        var pickerOpens = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.InSync, canChoosePhotos = true),
                actions = testActions(
                    access = testAccessActions(
                        onOpenSettings = { settingsOpens++ },
                        onChoosePhotos = { pickerOpens++ },
                    ),
                ),
            )
        }

        onNodeWithText(str(Res.string.choose_more_photos)).performClick()
        assertEquals(1, pickerOpens)
        assertEquals(0, settingsOpens)
    }

    @Test
    fun `no partial-grant affordances under a full grant`() = runComposeUiTest {
        // canChoosePhotos defaults false (permission != LIMITED) — neither offer renders.
        setContent { TestStatusScreen(inSync) }

        onNodeWithText(str(Res.string.choose_more_photos)).assertDoesNotExist()
        onNodeWithText(str(Res.string.allow_full_access)).assertDoesNotExist()
    }

    // ---- joined layer: name, leave, invite ----

    @Test
    fun `joined shows the event name as the title`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync) }
        onNodeWithText("Anna's Birthday").assertExists()
    }

    @Test
    fun `joined shows the leave action`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync) }
        onNodeWithText(str(Res.string.leave_event)).assertExists()
    }

    @Test
    fun `needs-access still shows leave and invite — sharing needs no access`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NeedsAccess(canAsk = false)),
            )
        }
        onNodeWithText(str(Res.string.leave_event)).assertExists()
        onNodeWithText(str(Res.string.share_invite)).assertExists()
        onNodeWithText(str(Res.string.show_qr)).assertExists()
    }

    @Test
    fun `activating leave asks for the confirmation`() = runComposeUiTest {
        var asked = 0
        setContent {
            TestStatusScreen(
                inSync,
                actions = testActions(surfaces = testSurfaceActions(onConfirmLeaveOpen = { asked++ })),
            )
        }
        onNodeWithText(str(Res.string.leave_title)).assertDoesNotExist()
        onNodeWithText(str(Res.string.leave_event)).performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `the leave confirmation renders when the state says it is up`() = runComposeUiTest {
        setContent { TestStatusScreen(confirmingLeave()) }
        onNodeWithText(str(Res.string.leave_title)).assertExists()
    }

    @Test
    fun `confirming leave invokes the callback`() = runComposeUiTest {
        var leaves = 0
        setContent {
            TestStatusScreen(
                confirmingLeave(),
                actions = testActions(joined = testJoinedActions(onLeaveEvent = { leaves++ })),
            )
        }

        onNode(hasText(str(Res.string.leave_confirm)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun `staying does not invoke leave and asks for the dialog to close`() = runComposeUiTest {
        var leaves = 0
        var dismissed = 0
        setContent {
            TestStatusScreen(
                confirmingLeave(),
                actions = testActions(
                    joined = testJoinedActions(onLeaveEvent = { leaves++ }),
                    surfaces = testSurfaceActions(onConfirmLeaveDismiss = { dismissed++ }),
                ),
            )
        }
        onNodeWithText(str(Res.string.leave_cancel)).performClick()
        assertEquals(0, leaves)
        assertEquals(1, dismissed)
    }

    @Test
    fun `joined offers both invites and shows no QR code until asked`() = runComposeUiTest {
        // Capability `manage-membership`: sharing the link and showing the QR code are two equal actions, and the
        // code itself is shown only on request.
        setContent { TestStatusScreen(inSync) }
        onNodeWithText(str(Res.string.share_invite)).assertExists()
        onNodeWithText(str(Res.string.show_qr)).assertExists()
        onNodeWithText(str(Res.string.invite_caption)).assertDoesNotExist()
    }

    @Test
    fun `show QR code asks for the sheet`() = runComposeUiTest {
        var opened = 0
        setContent {
            TestStatusScreen(
                inSync,
                actions = testActions(joined = testJoinedActions(onQrOpen = { opened++ })),
            )
        }
        onNodeWithText(str(Res.string.show_qr)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the QR sheet renders when the state says it is up`() = runComposeUiTest {
        val state = inSync.copy(overlays = inSync.overlays.copy(showingQr = true))
        setContent { TestStatusScreen(state) }
        val name = (state.layer as Layer.Joined).membership.name
        onNodeWithText(str(Res.string.qr_sheet_title, name)).assertExists()
        onNodeWithText(str(Res.string.invite_caption)).assertExists()
    }

    @Test
    fun `activating share invokes the callback`() = runComposeUiTest {
        var shares = 0
        setContent {
            TestStatusScreen(
                inSync,
                actions = testActions(joined = testJoinedActions(onShareInvite = { shares++ })),
            )
        }
        onNodeWithText(str(Res.string.share_invite)).performClick()
        assertEquals(1, shares)
    }

    // ---- the rename affordance + dialog (capability `manage-membership`) ----

    @Test
    fun `joined with a membership shows the rename pen beside the heading`() = runComposeUiTest {
        setContent {
            TestStatusScreen(inSync)
        }
        onNodeWithText("Anna's Birthday").assertExists()
        onNodeWithContentDescription(str(Res.string.rename_event)).assertExists()
    }

    @Test
    fun `the rename pen is present in every joined health — including without photo access`() = runComposeUiTest {
        // Renaming needs neither photo access nor a started event, so no health value may hide it.
        val health = mutableStateOf<SyncHealth>(SyncHealth.InSync)
        setContent {
            TestStatusScreen(
                joined(health.value),
            )
        }
        for (value in listOf(
            SyncHealth.InSync,
            SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN),
            SyncHealth.NeedsAccess(canAsk = false),
            SyncHealth.NeedsAccess(canAsk = true),
        )) {
            health.value = value
            waitForIdle()
            onNodeWithContentDescription(str(Res.string.rename_event)).assertExists()
        }
    }

    @Test
    fun `the rename pen stays offered while a pending switch is carried`() = runComposeUiTest {
        // It used to be suppressed here. `RenameEvent` guards the `eventId` itself, so a rename landing
        // across a switch renames nothing; hiding the control bought nothing, and it cost the pen for the
        // whole of every join's commit, which carries a pending join for the event being joined.
        setContent {
            TestStatusScreen(
                joined(
                    SyncHealth.InSync,
                    PendingSwitch(
                        "22222222-2222-4222-8222-222222222222",
                        JoinPhase.Detailed("New Event", JoinPhase.Detailed.Step.Ready),
                    ),
                ),
            )
        }
        onNodeWithContentDescription(str(Res.string.rename_event)).assertExists()
    }

    @Test
    fun `the joined screen stays beneath the open settings`() = runComposeUiTest {
        // The settings are a sheet OVER the joined screen (capability `manage-membership`): the heading is still there.
        setContent { TestStatusScreen(reconfiguring()) }
        onNodeWithText("Anna's Birthday").assertExists()
        onNodeWithText(str(Res.string.share_toggle)).assertExists()
    }

    @Test
    fun `the pen asks for the rename dialog`() = runComposeUiTest {
        var asked = 0
        setContent {
            TestStatusScreen(
                inSync,
                actions = testActions(surfaces = testSurfaceActions(onRenameOpen = { asked++ })),
            )
        }
        onNodeWithContentDescription(str(Res.string.rename_event)).performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `the rename dialog opens PRE-FILLED with the current name`() = runComposeUiTest {
        setContent {
            TestStatusScreen(renaming())
        }
        // The field opens carrying the current name, ready to be corrected rather than retyped.
        onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Anna's Birthday")),
        )
    }

    @Test
    fun `Save is inert while the name is unchanged and enables once it differs`() = runComposeUiTest {
        setContent {
            TestStatusScreen(renaming())
        }
        // A no-op rename must be unreachable, not merely rejected on a round trip.
        onNodeWithText(str(Res.string.save)).assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput("!")
        onNodeWithText(str(Res.string.save)).assertIsEnabled()
    }

    @Test
    fun `confirming submits the trimmed name with the membership’s event id`() = runComposeUiTest {
        val submitted = mutableListOf<Pair<String, String>>()
        setContent {
            TestStatusScreen(
                renaming(),
                actions = testActions(
                    joined = testJoinedActions(
                        onRenameEvent = { id, name -> submitted += id to name },
                    ),
                ),
            )
        }
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput("  Ana's 30th  ")
        onNodeWithText(str(Res.string.save)).performClick()
        // The id rides along so a switch landing mid-edit makes the use-case a no-op.
        assertEquals(listOf("E1" to "Ana's 30th"), submitted)
    }

    @Test
    fun `a failure keeps the dialog open with the typed value and an error BANNER`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                renaming(RenameState.Failed(ScreenMessage.RENAME_NAME_REFUSED)),
            )
        }
        // The sheet stays open — the failure is reported beside the field, never ON it: a server saying
        // no must not read as a complaint about the host's typing.
        onNodeWithText(str(Res.string.save)).assertExists()
        onNodeWithText(str(Res.string.message_rename_name_refused)).assertExists()
    }

    @Test
    fun `a server failure shows the generic copy — a swept event gets no special message`() = runComposeUiTest {
        // Deliberate: a 404 is ONE witness that the event is gone, and surfacing it would invite a future
        // change to act on it (capability `manage-membership`).
        setContent {
            TestStatusScreen(
                renaming(RenameState.Failed(ScreenMessage.RENAME_FAILED)),
            )
        }
        onNodeWithText(str(Res.string.message_rename_failed)).assertExists()
    }

    @Test
    fun `success closes the dialog and clears the latch`() = runComposeUiTest {
        var consumed = 0
        var dismissed = 0
        val status = mutableStateOf<RenameState>(RenameState.Idle)
        setContent {
            TestStatusScreen(
                renaming(status.value),
                actions = testActions(
                    joined = testJoinedActions(onRenameStatusConsumed = { consumed++ }),
                    surfaces = testSurfaceActions(onRenameDismiss = { dismissed++ }),
                ),
            )
        }
        onNodeWithText(str(Res.string.save)).assertExists()

        status.value = RenameState.Succeeded
        waitForIdle()

        // The sheet's CLOSING is the reduction's — the screen asks for it and clears the latch, so a
        // second rename starts from a clean sequence rather than re-reading this one's outcome.
        assertEquals(1, dismissed, "success asks for the sheet to close")
        assertEquals(1, consumed, "the latch is cleared so a second rename starts clean")
    }

    @Test
    fun `cancelling submits nothing`() = runComposeUiTest {
        var submits = 0
        var dismissed = 0
        setContent {
            TestStatusScreen(
                renaming(),
                actions = testActions(
                    joined = testJoinedActions(onRenameEvent = { _, _ -> submits++ }),
                    surfaces = testSurfaceActions(onRenameDismiss = { dismissed++ }),
                ),
            )
        }
        onNode(hasSetTextAction()).performTextInput("x")
        onNodeWithText(str(Res.string.cancel)).performClick()
        assertEquals(0, submits)
        assertEquals(1, dismissed, "cancelling asks for the sheet to close")
    }

    // ---- the settings action + reconfigure surface (capability `manage-membership`) ----

    @Test
    fun `joined with a membership shows the settings action next to share and leave`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync) }
        onNodeWithText(str(Res.string.footer_settings)).assertExists()
        onNodeWithText(str(Res.string.share_invite)).assertExists()
        onNodeWithText(str(Res.string.leave_event)).assertExists()
    }

    @Test
    fun `the settings action is present under needs-access — no photo access required`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NeedsAccess(canAsk = false)),
            )
        }
        onNodeWithText(str(Res.string.footer_settings)).assertExists()
    }

    @Test
    fun `the settings action stays offered while a pending switch is carried`() = runComposeUiTest {
        // The mirror of the rename pen above, retired for the same reason.
        setContent {
            TestStatusScreen(
                joined(
                    SyncHealth.InSync,
                    PendingSwitch(
                        "22222222-2222-4222-8222-222222222222",
                        JoinPhase.Detailed("New Event", JoinPhase.Detailed.Step.Ready),
                    ),
                ),
            )
        }
        onNodeWithText(str(Res.string.footer_settings)).assertExists()
    }

    /**
     * The exact shape reported in `SNAPSYNC-26`. A join's own commit carries a pending join for the event
     * being joined, so the reduction hands the screen a `Joined` with a `pendingSwitch` for the SAME event,
     * in the `Committing` phase, for as long as provisioning takes (3.26 s in the reported log).
     * `SwitchDialog` renders nothing for `Committing`, so the only thing that state ever changed was these
     * two controls — which is the whole of the reported symptom.
     */
    @Test
    fun `a join’s own commit leaves the heading and cluster controls in place`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(
                    SyncHealth.InSync,
                    PendingSwitch(
                        MEMBERSHIP.eventId,
                        JoinPhase.Detailed("Anna's Birthday", JoinPhase.Detailed.Step.Committing),
                    ),
                ),
            )
        }
        onNodeWithText(str(Res.string.footer_settings)).assertExists()
        onNodeWithContentDescription(str(Res.string.rename_event)).assertExists()
        // The two neighbours that were never suppressed, asserted alongside so the row is checked whole.
        onNodeWithText(str(Res.string.share_invite)).assertExists()
        onNodeWithText(str(Res.string.leave_event)).assertExists()
    }

    @Test
    fun `tapping settings opens the reconfigure surface`() = runComposeUiTest {
        var opened = 0
        setContent {
            TestStatusScreen(
                inSync,
                actions = testActions(surfaces = testSurfaceActions(onOpenReconfigure = { opened++ })),
            )
        }
        onNodeWithText(str(Res.string.save)).assertDoesNotExist()
        onNodeWithText(str(Res.string.footer_settings)).performClick()
        assertEquals(1, opened, "the gear asks the container to open the surface")
    }

    @Test
    fun `the settings show every choice and no Save or Cancel`() = runComposeUiTest {
        // Each change applies as it is made (capability `manage-membership`): there is nothing to commit or discard.
        setContent { TestStatusScreen(reconfiguring()) }
        for (choice in listOf(Res.string.share_toggle, Res.string.receive_toggle, Res.string.album_toggle)) {
            onNodeWithText(str(choice)).assertExists()
        }
        // The mobile-data choice is the device's, in the app menu (decision record `changes/archive/2026-10-07-mobile-data-per-device`).
        onNodeWithText(str(Res.string.mobile_data_toggle)).assertDoesNotExist()
        onNodeWithText(str(Res.string.save)).assertDoesNotExist()
        onNodeWithText(str(Res.string.cancel)).assertDoesNotExist()
    }

    @Test
    fun `a change that did not land is said at the top of the settings`() = runComposeUiTest {
        setContent { TestStatusScreen(reconfiguring(saveFailed = true)) }
        onNodeWithText(str(Res.string.settings_save_failed)).assertExists()
    }

    @Test
    fun `an open surface with no failed save shows no failure`() = runComposeUiTest {
        setContent { TestStatusScreen(reconfiguring()) }
        onNodeWithText("couldn't be saved", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the reconfigure surface shows the whole event when the range spans the window`() = runComposeUiTest {
        // minPhotoDate == startsAt and maxPhotoDate == endsAt → the whole event. The row states the full window
        // as the compact adaptive range.
        setContent { TestStatusScreen(reconfiguring()) }
        onNodeWithText("6 Jul, 12:00 – 10 Jul, 12:00").assertExists()
        onNodeWithText(str(Res.string.range_whole_event), substring = true).assertExists()
    }

    @Test
    fun `the reconfigure surface shows a custom range when the cutoff is above the floor`() = runComposeUiTest {
        val above = MEMBERSHIP.copy(sharesFrom = LocalDateTime(2026, 7, 6, 18, 0))
        val form = Choices(preset = RangeChoice.CUSTOM, customFrom = LocalDateTime(2026, 7, 6, 18, 0))
        setContent { TestStatusScreen(reconfiguring(above, form)) }
        onNodeWithText("6 Jul, 18:00 – 10 Jul, 12:00").assertExists()
        onNodeWithText(str(Res.string.range_custom), substring = true).assertExists()
    }

    @Test
    fun `the reconfigure surface shows a custom range when the ceiling is below the event end`() = runComposeUiTest {
        val below = MEMBERSHIP.copy(sharesUntil = LocalDateTime(2026, 7, 9, 12, 0))
        val form = Choices(preset = RangeChoice.CUSTOM, customUntil = LocalDateTime(2026, 7, 9, 12, 0))
        setContent { TestStatusScreen(reconfiguring(below, form)) }
        onNodeWithText("6 Jul, 12:00 – 9 Jul, 12:00").assertExists()
        onNodeWithText(str(Res.string.range_custom), substring = true).assertExists()
    }

    @Test
    fun `turning the album on says the already-synced photos are included`() = runComposeUiTest {
        val withAlbum = MEMBERSHIP.copy(savesToAlbum = true)
        setContent {
            TestStatusScreen(reconfiguring(withAlbum, Choices(saveToAlbum = true)))
        }
        onNodeWithText("including the ones you already have", substring = true).assertExists()
        onNodeWithText("from now on", substring = true).assertDoesNotExist()
    }

    @Test
    fun `turning a folder album on says the already-received photos are included and own photos stay`() = runComposeUiTest {
        // Capability `manage-membership`: on Android the album gathers what was received, never the member's own photos.
        val withAlbum = MEMBERSHIP.copy(savesToAlbum = true)
        setContent {
            TestStatusScreen(
                reconfiguring(withAlbum, Choices(saveToAlbum = true, albumStyle = AlbumStyle.FOLDER)),
            )
        }
        onNodeWithText("including the ones already received", substring = true).assertExists()
        onNodeWithText("Your own photos stay where they are", substring = true).assertExists()
    }

    @Test
    fun `withdrawing photos asks first and each answer reaches the container`() = runComposeUiTest {
        var stopped = 0
        var kept = 0
        val state = mutableStateOf(reconfiguring(askingToStopSharing = true))
        setContent {
            TestStatusScreen(
                state.value,
                actions = testActions(
                    joined = testJoinedActions(onStopSharing = { stopped++ }, onKeepSharing = { kept++ }),
                ),
            )
        }
        onNodeWithText(str(Res.string.stop_sharing_title)).assertExists()
        // The narrowing statement: whoever already has the photos keeps them.
        onNodeWithText(str(Res.string.stop_sharing_body)).assertExists()
        onNodeWithText(str(Res.string.stop_sharing_confirm)).performClick()
        assertEquals(1 to 0, stopped to kept)
        onNodeWithText(str(Res.string.stop_sharing_keep)).performClick()
        assertEquals(1 to 1, stopped to kept)
        state.value = reconfiguring()
        waitForIdle()
        onNodeWithText(str(Res.string.stop_sharing_title)).assertDoesNotExist()
    }

    @Test
    fun `a tap on the joined screen above the settings closes them`() = runComposeUiTest {
        var closed = 0
        setContent {
            TestStatusScreen(
                reconfiguring(),
                actions = testActions(surfaces = testSurfaceActions(onCancelReconfigure = { closed++ })),
            )
        }
        // The scrim spans the screen and its centre is under the sheet: tap the strip it shows at the top.
        onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(centerX, 5f)) }
        waitForIdle()
        assertEquals(1, closed)
    }

    // ---- joined layer: the heading's joined statement and dates (capability `sync-status`) ----

    @Test
    fun `the heading says the device has joined and how long the event lasts`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync) }
        onNodeWithText("Anna's Birthday").assertExists()
        onNodeWithText(str(Res.string.joined_statement)).assertExists()
        onNodeWithText(
            "Mon 6 Jul – Fri 10 Jul" + " · " + str(Res.string.timing_ends_in, plural(Res.plurals.duration_days, 4, 4)),
        ).assertExists()
    }

    @Test
    fun `the last day counts down in hours and then minutes`() = runComposeUiTest {
        val state = mutableStateOf(joined(SyncHealth.InSync, timing = EventTiming.Running(TimeLeft.Hours(5))))
        setContent { TestStatusScreen(state.value) }
        onNodeWithText(
            "Mon 6 Jul – Fri 10 Jul" + " · " + str(Res.string.timing_ends_in, plural(Res.plurals.duration_hours, 5, 5)),
        ).assertExists()
        state.value = joined(SyncHealth.InSync, timing = EventTiming.Running(TimeLeft.Minutes(40)))
        onNodeWithText(
            "Mon 6 Jul – Fri 10 Jul" + " · " + str(Res.string.timing_ends_in, plural(Res.plurals.duration_minutes_short, 40, 40)),
        ).assertExists()
        state.value = joined(SyncHealth.InSync, timing = EventTiming.Running(TimeLeft.Hours(1)))
        onNodeWithText(
            "Mon 6 Jul – Fri 10 Jul" + " · " + str(Res.string.timing_ends_in, plural(Res.plurals.duration_hours, 1, 1)),
        ).assertExists()
    }

    @Test
    fun `a same-day event today shows its times`() = runComposeUiTest {
        val party = MEMBERSHIP.copy(
            startsAt = LocalDateTime(2026, 7, 6, 18, 0),
            endsAt = LocalDateTime(2026, 7, 6, 23, 0),
        )
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NotStarted, membership = party, timing = EventTiming.Upcoming(TimeLeft.Hours(6))),
            )
        }
        onNodeWithText(
            str(ComponentRes.string.date_range_today, "18:00", "23:00") + " · " + str(Res.string.timing_starts_in, plural(Res.plurals.duration_hours, 6, 6)),
        ).assertExists()
    }

    @Test
    fun `an ended event says so on the dates line and never in the status text`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN), timing = EventTiming.Ended),
            )
        }
        onNodeWithText("Mon 6 Jul – Fri 10 Jul" + " · " + str(Res.string.timing_ended)).assertExists()
        onNodeWithText("Event ended", substring = true).assertDoesNotExist()
        onNodeWithText(str(ComponentRes.string.status_sync_pending)).assertExists()
    }

    // ---- joined layer: the counts line (capability `sync-status`) ----

    @Test
    fun `the counts show both directions while work remains`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(
                    SyncHealth.Syncing(Arrow.PULSING, Arrow.STATIC),
                    counts = SyncCounts(progress(12, 15), progress(40, 52)),
                ),
            )
        }
        onNodeWithText(str(ComponentRes.string.status_sync_ongoing)).assertExists()
        onNodeWithText(
            str(
                Res.string.counts_line,
                str(Res.string.counts_shared_progress, 12, 15),
                str(Res.string.counts_received_progress, 40, 52),
            ),
        ).assertExists()
    }

    @Test
    fun `in sync the counts show totals`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(SyncHealth.InSync, counts = SyncCounts(progress(15, 15), progress(52, 52))),
            )
        }
        onNodeWithText(str(ComponentRes.string.status_in_sync)).assertExists()
        onNodeWithText(
            str(Res.string.counts_line, str(Res.string.counts_shared, 15), str(Res.string.counts_received, 52)),
        ).assertExists()
    }

    @Test
    fun `a switched-off direction says so`() = runComposeUiTest {
        val state = mutableStateOf(
            joined(
                SyncHealth.Syncing(Arrow.HIDDEN, Arrow.STATIC),
                counts = SyncCounts(DirectionCount.Off, progress(40, 52)),
            ),
        )
        setContent { TestStatusScreen(state.value) }
        onNodeWithText(
            str(
                Res.string.counts_line,
                str(Res.string.counts_not_sharing),
                str(Res.string.counts_received_progress, 40, 52),
            ),
        ).assertExists()
        state.value = joined(SyncHealth.InSync, counts = SyncCounts(progress(15, 15), DirectionCount.Off))
        onNodeWithText(
            str(Res.string.counts_line, str(Res.string.counts_shared, 15), str(Res.string.counts_not_receiving)),
        ).assertExists()
    }

    @Test
    fun `no counts are drawn when the state carries none`() = runComposeUiTest {
        setContent { TestStatusScreen(joined(SyncHealth.NeedsAccess(canAsk = false))) }
        onNode(countsText()).assertDoesNotExist()
    }

    @Test
    fun `limited access shows its counts beside the two access offers`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(
                    SyncHealth.InSync,
                    canChoosePhotos = true,
                    counts = SyncCounts(progress(6, 6), progress(52, 52)),
                ),
            )
        }
        onNodeWithText(
            str(Res.string.counts_line, str(Res.string.counts_shared, 6), str(Res.string.counts_received, 52)),
        ).assertExists()
        onNodeWithText(str(Res.string.choose_more_photos)).assertExists()
        onNodeWithText(str(Res.string.allow_full_access)).assertExists()
    }

    // ---- joined layer: early completion (capabilities `sync-status`, `manage-membership`) ----

    @Test
    fun `an ended event says how many members it is still waiting for beneath the counts`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(
                    SyncHealth.InSync,
                    timing = EventTiming.Ended,
                    members = 5,
                    sharingNote = SharingNote.WaitingFor(2),
                    counts = SyncCounts(progress(34, 34), progress(110, 110)),
                ),
            )
        }
        onNodeWithText(
            str(
                Res.string.counts_line_members,
                plural(Res.plurals.members_count, 5, 5),
                str(Res.string.counts_shared, 34),
                str(Res.string.counts_received, 110),
            ),
        ).assertExists()
        onNodeWithText(plural(Res.plurals.waiting_members, 2, 2)).assertExists()
        onNodeWithText(str(ComponentRes.string.status_in_sync)).assertExists()
    }

    @Test
    fun `once everyone has shared the ended event says so`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                joined(
                    SyncHealth.InSync,
                    timing = EventTiming.Ended,
                    members = 4,
                    sharingNote = SharingNote.EveryoneShared,
                    counts = SyncCounts(progress(34, 34), progress(58, 58)),
                ),
            )
        }
        onNodeWithText(str(Res.string.everyone_shared)).assertExists()
    }

    @Test
    fun `the member count stands alone when there are no photo counts`() = runComposeUiTest {
        setContent { TestStatusScreen(joined(SyncHealth.NeedsAccess(canAsk = true), members = 1)) }
        onNodeWithText(plural(Res.plurals.members_count, 1, 1)).assertExists()
    }

    @Test
    fun `a back-dated event says until when others can join`() = runComposeUiTest {
        setContent {
            val ended = joined(SyncHealth.InSync, timing = EventTiming.Ended).layer as Layer.Joined
            TestStatusScreen(UiState(ended.copy(joinableUntil = LocalDateTime(2026, 7, 13, 12, 0))))
        }
        onNode(hasText(str(Res.string.joinable_until, ""), substring = true)).assertExists()
    }

    @Test
    fun `a deleted event is shown as over and offers only Leave which does not ask`() = runComposeUiTest {
        var leaves = 0
        var asked = 0
        setContent {
            TestStatusScreen(
                joined(
                    SyncHealth.InSync,
                    timing = EventTiming.Ended,
                    deleted = true,
                    counts = SyncCounts(progress(34, 34), progress(58, 58)),
                ),
                actions = testActions(
                    joined = testJoinedActions(onLeaveEvent = { leaves++ }),
                    surfaces = testSurfaceActions(onConfirmLeaveOpen = { asked++ }),
                ),
            )
        }
        onNodeWithText(str(Res.string.event_over_title)).assertExists()
        onNodeWithText(str(Res.string.event_over_body)).assertExists()
        onNodeWithText(
            str(Res.string.counts_line, str(Res.string.counts_shared, 34), str(Res.string.counts_received, 58)),
        ).assertExists()
        onNodeWithText(str(Res.string.share_invite)).assertDoesNotExist()
        onNodeWithText(str(Res.string.footer_settings)).assertDoesNotExist()
        onNodeWithContentDescription(str(Res.string.rename_event)).assertDoesNotExist()
        onNodeWithText(str(Res.string.show_qr)).assertDoesNotExist()
        onNodeWithText(str(Res.string.joined_statement)).assertDoesNotExist()
        onNodeWithText(str(Res.string.explain_share_title)).assertDoesNotExist()
        onNodeWithText(str(ComponentRes.string.status_in_sync)).assertDoesNotExist()
        // The name and the dates stay.
        onNodeWithText("Mon 6 Jul – Fri 10 Jul" + " · " + str(Res.string.timing_ended)).assertExists()
        onNodeWithText(str(Res.string.leave_event)).performClick()
        assertEquals(1, leaves)
        assertEquals(0, asked)
    }

    // ---- how it works (capability `sync-status`) ----

    @Test
    fun `a sharing and receiving member is told what happens to both sides and what to do when photos stall`() =
        runComposeUiTest {
            setContent {
                TestStatusScreen(
                    joined(SyncHealth.InSync, membership = MEMBERSHIP.copy(savesToAlbum = true)),
                )
            }
            onNodeWithText(str(Res.string.explain_share_title)).assertExists()
            onNodeWithText(str(Res.string.explain_share_caption, RANGE)).assertExists()
            onNodeWithText(str(Res.string.explain_receive_title)).assertExists()
            onNodeWithText(str(Res.string.explain_receive_album, MEMBERSHIP.name)).assertExists()
            onNodeWithText(str(Res.string.explain_hint_title)).assertExists()
            onNodeWithText(str(Res.string.explain_hint_caption)).assertExists()
        }

    @Test
    fun `the range named is the member's own and not the event's`() = runComposeUiTest {
        val part = MEMBERSHIP.copy(sharesFrom = LocalDateTime(2026, 7, 8, 12, 0))
        setContent { TestStatusScreen(joined(SyncHealth.InSync, membership = part)) }
        onNodeWithText(str(Res.string.explain_share_caption, "Wed 8 Jul – Fri 10 Jul")).assertExists()
    }

    @Test
    fun `without an album the group's photos land beside the member's own`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync) }
        onNodeWithText(str(Res.string.explain_receive_no_album)).assertExists()
    }

    @Test
    fun `sharing switched off says so and its link opens the event's settings`() = runComposeUiTest {
        var opened = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.InSync, membership = MEMBERSHIP.copy(shares = false)),
                actions = testActions(surfaces = testSurfaceActions(onOpenReconfigure = { opened++ })),
            )
        }
        onNodeWithText(str(Res.string.explain_share_off_title)).assertExists()
        onNodeWithText(str(Res.string.explain_share_title)).assertDoesNotExist()
        onNodeWithText(str(Res.string.explain_receive_title)).assertExists()
        onNodeWithText(
            caption(Res.string.explain_share_off_caption, Res.string.explain_settings_link, RANGE),
        ).performFirstLinkClick()
        assertEquals(1, opened)
    }

    @Test
    fun `receiving switched off says so and its link opens the event's settings`() = runComposeUiTest {
        var opened = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.InSync, membership = MEMBERSHIP.copy(receives = false)),
                actions = testActions(surfaces = testSurfaceActions(onOpenReconfigure = { opened++ })),
            )
        }
        onNodeWithText(str(Res.string.explain_receive_off_title)).assertExists()
        onNodeWithText(
            caption(Res.string.explain_receive_off_caption, Res.string.explain_settings_link),
        ).performFirstLinkClick()
        assertEquals(1, opened)
    }

    @Test
    fun `without access nothing is shared or received and the link asks exactly as the status line does`() =
        runComposeUiTest {
            var asked = 0
            var settings = 0
            setContent {
                TestStatusScreen(
                    joined(SyncHealth.NeedsAccess(canAsk = true)),
                    actions = testActions(
                        access = testAccessActions(onRequestPermission = { asked++ }, onOpenSettings = { settings++ }),
                    ),
                )
            }
            onNodeWithText(str(Res.string.explain_share_blocked_title)).assertExists()
            onNodeWithText(str(Res.string.explain_receive_blocked_title)).assertExists()
            onNodeWithText(
                caption(Res.string.explain_share_blocked_caption, Res.string.explain_access_link, RANGE),
            ).performFirstLinkClick()
            onNodeWithText(
                caption(Res.string.explain_receive_blocked_caption, Res.string.explain_access_link),
            ).performFirstLinkClick()
            assertEquals(2, asked)
            assertEquals(0, settings)
        }

    @Test
    fun `a refused grant's link goes to the phone's Settings`() = runComposeUiTest {
        var settings = 0
        setContent {
            TestStatusScreen(
                joined(SyncHealth.NeedsAccess(canAsk = false)),
                actions = testActions(access = testAccessActions(onOpenSettings = { settings++ })),
            )
        }
        onNodeWithText(
            caption(Res.string.explain_receive_blocked_caption, Res.string.explain_access_link),
        ).performFirstLinkClick()
        assertEquals(1, settings)
    }

    @Test
    fun `limited access names the selection and offers both ways to widen it`() = runComposeUiTest {
        setContent { TestStatusScreen(joined(SyncHealth.InSync, canChoosePhotos = true)) }
        onNodeWithText(str(Res.string.explain_share_limited_title)).assertExists()
        onNodeWithText(str(Res.string.explain_share_limited_caption, RANGE)).assertExists()
        onNodeWithText(str(Res.string.choose_more_photos)).assertExists()
        onNodeWithText(str(Res.string.allow_full_access)).assertExists()
    }

    @Test
    fun `full access shows neither widening choice`() = runComposeUiTest {
        setContent { TestStatusScreen(inSync) }
        onNodeWithText(str(Res.string.choose_more_photos)).assertDoesNotExist()
        onNodeWithText(str(Res.string.allow_full_access)).assertDoesNotExist()
    }

    @Test
    fun `on the smallest phone the footer stays on screen while the content scrolls`() = runComposeUiTest {
        // An SE2's screen in points, with the tallest joined screen: the no-access rows and a notice-free status.
        setContent {
            Box(Modifier.requiredSize(SE2_WIDTH, SE2_HEIGHT)) {
                TestStatusScreen(
                    joined(SyncHealth.NeedsAccess(canAsk = false), canChoosePhotos = false),
                )
            }
        }
        for (label in listOf(
            Res.string.share_invite,
            Res.string.show_qr,
            Res.string.footer_settings,
            Res.string.leave_event,
        )) {
            val bottom = onNodeWithText(str(label)).getUnclippedBoundsInRoot().bottom
            assertTrue(bottom <= SE2_HEIGHT, "${str(label)} ends at $bottom, below the screen")
        }
        onNodeWithText(str(Res.string.explain_hint_title)).performScrollTo().assertIsDisplayed()
    }

    // ---- the screens' remaining choices ----

    @Test
    fun `a link rejected while joined is said above the joined screen`() = runComposeUiTest {
        val state = inSync.copy(layer = (inSync.layer as Layer.Joined).copy(notice = ScreenMessage.INVALID_LINK))
        setContent { TestStatusScreen(state) }
        onNodeWithText(str(Res.string.message_invalid_link)).assertExists()
        onNodeWithText(MEMBERSHIP.name).assertExists()
    }

    @Test
    fun `a report offered for a message with no description of its own opens empty`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                inSync.copy(overlays = Overlays(reportingBug = true, reportSeed = ScreenMessage.DEVICE_MODIFIED)),
            )
        }
        assertEquals("", onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun `the rename sheet and the QR sheet need a joined event to show`() = runComposeUiTest {
        setContent {
            TestStatusScreen(
                UiState(createLayer(), Overlays(renaming = true, showingQr = true)),
            )
        }
        onNodeWithText(str(Res.string.rename_body)).assertDoesNotExist()
        onNodeWithText(str(Res.string.invite_caption)).assertDoesNotExist()
        onNodeWithText(str(Res.string.create_title)).assertExists()
    }

    /** A node showing a count ("12/15 shared", "40 received") — the explanation's words are not counts. */
    private fun countsText(): SemanticsMatcher = SemanticsMatcher("shows a count") { node ->
        node.config.getOrElseNullable(
            SemanticsProperties.Text,
        ) { null }.orEmpty().any { COUNT.containsMatchIn(it.toString()) }
    }

    private fun hasAnyProgressIndication(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
}

private fun samePixels(a: PixelMap, b: PixelMap): Boolean {
    if (a.width != b.width || a.height != b.height) return false
    for (y in 0 until a.height) for (x in 0 until a.width) if (a[x, y] != b[x, y]) return false
    return true
}

/** A keyboard controller that counts what it was asked to do. */
internal class CountingKeyboard : SoftwareKeyboardController {
    var shown = 0

    override fun show() {
        shown++
    }

    override fun hide() = Unit
}
