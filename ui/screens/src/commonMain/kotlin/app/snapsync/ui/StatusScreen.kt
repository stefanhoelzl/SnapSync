package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.PermissionStatus
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.PendingSwitch
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import app.snapsync.ui.components.AppAccessPoint
import app.snapsync.ui.components.AppConfirmDialog
import app.snapsync.ui.components.AppDestructiveConfirmDialog
import app.snapsync.ui.components.AppErrorBanner
import app.snapsync.ui.components.AppEventHeaderHost
import app.snapsync.ui.components.AppEventStartSection
import app.snapsync.ui.components.AppEyebrow
import app.snapsync.ui.components.AppInvitationHeaderLoading
import app.snapsync.ui.components.AppJoinProgress
import app.snapsync.ui.components.AppNoticeCard
import app.snapsync.ui.components.AppSummaryCard
import app.snapsync.ui.components.EyebrowTone
import app.snapsync.ui.components.JOIN_HERO_SUBTITLE
import app.snapsync.ui.components.JoinAccessChoose
import app.snapsync.ui.components.JoinAccessCutoff
import app.snapsync.ui.components.JoinAccessLibrary
import app.snapsync.ui.components.JoinAccessShare
import app.snapsync.ui.components.JoinNoticeFailed
import app.snapsync.ui.components.JoinNoticeInvalid
import app.snapsync.ui.components.JoinNoticeOffline
import kotlinx.datetime.LocalDateTime
import app.snapsync.ui.components.AppQrCode
import app.snapsync.ui.components.AccessPrompt
import app.snapsync.ui.components.AppCutoffChoices
import app.snapsync.ui.components.CutoffChoice
import app.snapsync.ui.components.AppEventHeaderCompact
import app.snapsync.ui.components.AppQuestionHeading
import app.snapsync.ui.components.AppSectionNote
import app.snapsync.ui.components.AppMinorSection
import app.snapsync.ui.components.AppSubSection
import app.snapsync.ui.components.AppSectionValue
import app.snapsync.ui.components.AppSummaryToggle
import app.snapsync.ui.components.AppToggleSection
import app.snapsync.ui.components.appDateTimeLabel
import app.snapsync.ui.components.AppStatusLine
import app.snapsync.ui.components.AppSyncStatus
import app.snapsync.ui.components.AppTextField
import app.snapsync.ui.components.AppTheme
import app.snapsync.ui.components.LeaveButton
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.ScreenLayout
import app.snapsync.ui.components.SecondaryButton
import app.snapsync.ui.components.SettingsButton
import app.snapsync.ui.components.ShareButton
import app.snapsync.ui.components.StatusHero
import app.snapsync.ui.components.StatusHint
import app.snapsync.ui.components.StatusIndicator

@Composable
fun StatusScreen(
    state: UiState,
    onRequestPermission: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onLeaveEvent: () -> Unit = {},
    onShareInvite: () -> Unit = {},
    // The joined membership's current settings, for the in-place reconfigure surface (capability
    // `reconfigure-membership`); null when no event is configured. The settings gear opens a full-screen
    // surface pre-filled from this, and Save fires [onReconfigure]. A screen param like [inviteUrl] —
    // opening/closing the surface is screen-local navigation, so no external "open" callback is threaded.
    membership: EventConfig? = null,
    // Commit an in-place reconfigure (capability `reconfigure-membership`): the event the surface was
    // opened for, the new direction, the chosen cutoff (clamped to the floor on the far side, in
    // `ReconfigureEvent`), and the album opt-in.
    onReconfigure: (String, Direction, String, Boolean) -> Unit = { _, _, _, _ -> },
    inviteUrl: String? = null,
    // The joined event's name (fetched by id), shown as the heading; null until fetched.
    eventName: String? = null,
    onCreateEvent: (String, LocalDateTime) -> Unit = { _, _ -> },
    transientError: String? = null,
    // Join-gate actions (capability `join-event`), routed to the container intents. The confirm/retry
    // actions carry the chosen capture-date cutoff (capability `photo-selection-policy`; always present),
    // the chosen participation direction (capability `join-event`), and the album opt-in (`saveToAlbum`,
    // capability `event-album`).
    onConfirmJoin: (String, Direction, Boolean) -> Unit = { _, _, _ -> },
    // The photo-access explainer's confirm: requests permission, then advances to the confirm surface.
    // The only route from the join gate to the system dialog (capability `join-event`).
    onAcknowledgeAccess: () -> Unit = {},
    // The joined layer's "Choose more photos" tap under a partial grant (capability
    // `limited-photo-access`): presents the system limited-library picker.
    onChoosePhotos: () -> Unit = {},
    onCancelJoin: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    onRetryJoin: (String, Direction, Boolean) -> Unit = { _, _, _ -> },
    onConfirmSwitch: (String, Direction) -> Unit = { _, _ -> },
    onCancelSwitch: () -> Unit = {},
    // Bridges the cutoff picker (local wall-clock) to the UTC `…Z` cutoff string. Required — with NO
    // system-reading default (migration step 9): the host binds the `Clock`/`TimeZoneSource` ports
    // (production) or a fixed instant/zone (tests); this screen holds no clock or timezone knowledge.
    cutoff: CutoffFormatter,
    // The join-time shareable-count preview (capability `join-share-count`): given the chosen cutoff, how
    // many of the member's own gallery photos would be shared. `null` = no count available (DENIED /
    // unresolved grant) → the row is omitted. Permission-aware and cheap (no per-asset resource read) — the
    // permission-branch and the LIMITED snapshot live inside the compose-built query. Default `{ null }`
    // keeps the row absent wherever it is not wired (forge, plain tests).
    shareableCount: suspend (cutoff: String) -> Int? = { null },
    // The current photo-access grant, threaded purely as a recompute trigger for the count: a late resolve
    // (the first-join dialog is answered a beat after Ready renders) must make the count appear.
    photoPermission: PermissionStatus = PermissionStatus.GRANTED,
) {
    AppTheme {
        // Local UI state only: the confirm dialog's visibility never enters UiState or the reduction.
        var confirmingLeave by remember { mutableStateOf(false) }
        // The reconfigure surface's visibility is likewise screen-local navigation (capability
        // `reconfigure-membership`, design decision "local Compose navigation"): opening it touches no
        // port; only Save fires a command. Like [confirmingLeave], it never enters UiState.
        var reconfiguring by remember { mutableStateOf(false) }

        val joined = state is UiState.Joined
        val pendingSwitch = (state as? UiState.Joined)?.pendingSwitch != null
        // The reconfigure surface renders only while joined with a known membership; if the config drops
        // (a leave lands) the flag is reset so a later rejoin does not reopen it.
        val reconfigureActive = joined && reconfiguring && membership != null
        LaunchedEffect(joined) { if (!joined) reconfiguring = false }

        // The joined-layer action cluster: settings · share · leave. Settings is suppressed during a
        // pending switch (a reconfigure must not race the switch's config write) and needs a known
        // membership; sharing needs no photo access, and leave always shows. Hidden while the reconfigure
        // surface is open (it pins its own Save/Cancel). Loading and the create layer show none.
        val bottomActions: (@Composable () -> Unit)? = if (joined && !reconfigureActive) {
            {
                if (!pendingSwitch && membership != null) {
                    SettingsButton(description = "Event settings", onClick = { reconfiguring = true })
                }
                if (inviteUrl != null) {
                    ShareButton(description = "Share invite link", onClick = onShareInvite)
                }
                LeaveButton(description = "Leave event", onClick = { confirmingLeave = true })
            }
        } else {
            null
        }

        // The app-name nav label is always "SnapSync"; the joined event's name is the prominent heading.
        ScreenLayout(
            title = "SnapSync",
            heading = if (joined && !reconfigureActive) eventName else null,
            bottomActions = bottomActions,
            // Every join phase pins Cancel (and, on Ready, Join) as its own full-width bottom cluster; the
            // reconfigure surface likewise pins its own Save/Cancel — so both take the safe-area-anchored
            // bottom edge with no jump.
            contentPinsActionCluster = state is UiState.JoiningEvent || reconfigureActive,
        ) {
            if (reconfigureActive) {
                ReconfigureScreen(
                    membership = membership!!,
                    cutoff = cutoff,
                    shareableCount = shareableCount,
                    photoPermission = photoPermission,
                    onSave = { eventId, direction, chosenCutoff, saveToAlbum ->
                        reconfiguring = false
                        onReconfigure(eventId, direction, chosenCutoff, saveToAlbum)
                    },
                    onCancel = { reconfiguring = false },
                )
            } else when (state) {
                is UiState.CreateEvent ->
                    CreateEventScreen(state, onCreateEvent, transientError, cutoff)
                UiState.CreatingEvent ->
                    CreatingEventScreen()
                is UiState.JoiningEvent ->
                    JoiningEventScreen(
                        state.phase, cutoff, onConfirmJoin, onAcknowledgeAccess,
                        onCancelJoin, onRetryLoad, onRetryJoin,
                        shareableCount, photoPermission,
                    )
                is UiState.Joined ->
                    JoinedLayer(
                        state.health, inviteUrl, onRequestPermission, onOpenSettings,
                        state.canChoosePhotos, onChoosePhotos, cutoff,
                    )
            }
        }

        if (confirmingLeave) {
            AppDestructiveConfirmDialog(
                title = "Leave this event?",
                body = "You'll stop sharing and receiving photos. Photos already in your " +
                    "library stay.",
                confirmLabel = "Leave",
                cancelLabel = "Stay",
                onConfirm = {
                    confirmingLeave = false
                    onLeaveEvent()
                },
                onDismiss = { confirmingLeave = false },
            )
        }

        // A switch confirmation over the joined screen (scanning a different event while joined).
        (state as? UiState.Joined)?.pendingSwitch?.let { switch ->
            SwitchDialog(
                switch = switch,
                currentEventName = eventName,
                onConfirmSwitch = onConfirmSwitch,
                onCancelSwitch = onCancelSwitch,
                onRetryLoad = onRetryLoad,
                // The compact switch path has no album picker — a retry there is album-off.
                onRetryJoin = { cutoff, direction -> onRetryJoin(cutoff, direction, false) },
                shareableCount = shareableCount,
                photoPermission = photoPermission,
            )
        }
    }
}

/**
 * The loaded `createdAt` default, carried by the two phases that have one. `null` everywhere else
 * (`Loading` before the fetch resolves; `NotFound`/`LoadFailed`; `Committing`/`CommitFailed` after the
 * confirm) — which is why the cutoff row seeds from the first phase that *does* carry one, not from
 * whichever phase the screen happened to mount at.
 */
/**
 * The event's start, from whichever phase carries it (capability `photo-selection-policy`).
 *
 * Unlike the seed it replaces, this covers **Committing and CommitFailed too**. Those phases carry
 * `startsAt` precisely because a Retry commits WITHOUT passing back through the loaded phase — reading it
 * only from `Ready` would make a retry derive its cutoff from `now` instead of the start the user chose,
 * silently discarding their selection at the one moment they are already recovering from a failure.
 */
private fun JoinPhase.startsAt(): String? = when (this) {
    is JoinPhase.ExplainAccess -> startsAt
    is JoinPhase.Ready -> startsAt
    is JoinPhase.Committing -> startsAt
    is JoinPhase.CommitFailed -> startsAt
    JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed -> null
}

/**
 * The full-screen "Join event" surface (capability `join-event`): the event summary is the hero, with
 * the participation-direction row, the capture-date cutoff row (capability `photo-selection-policy`), and the
 * save-to-album opt-in (capability `event-album`), with Join / Cancel pinned to the bottom. Further future
 * options slot in as more rows in this same column. Renders each [JoinPhase]: loading details,
 * ready-to-join, blocked (invalid invite), a retryable load/commit failure.
 *
 * The chosen direction and cutoff are held in local state: the direction defaults to [Direction.Both];
 * the cutoff is seeded once, **non-null**, from the loaded default (`createdAt`, already resolved to now by
 * the host when the marker carried none), editable via the date/time picker or snapped to "now", and
 * converted to the UTC `…Z` string on confirm/retry. Both survive Ready → Committing → CommitFailed (the
 * composable stays mounted), so a retry reuses them. The cutoff row is disabled under
 * [Direction.DownloadOnly] (it scopes uploads only).
 */
@Composable
private fun JoiningEventScreen(
    phase: JoinPhase,
    cutoff: CutoffFormatter,
    onConfirm: (String, Direction, Boolean) -> Unit,
    onAcknowledgeAccess: () -> Unit,
    onCancel: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryJoin: (String, Direction, Boolean) -> Unit,
    shareableCount: suspend (cutoff: String) -> Int?,
    photoPermission: PermissionStatus,
) {
    // The two participation switches, both default ON. Direction is DERIVED from them, never chosen:
    // share+receive → Both, share only → UploadOnly, receive only → DownloadOnly. There is deliberately no
    // "no photos" option in either — "not sharing" IS the share switch off, "not receiving" the receive
    // switch off. Both off is representable and does nothing; Join is disabled with a stated reason rather
    // than one switch silently flipping the other.
    var shareOn by remember { mutableStateOf(true) }
    var receiveOn by remember { mutableStateOf(true) }

    // The cutoff is a preset (capability `photo-selection-policy`), defaulting to the event's start — so a
    // share with no cutoff is unrepresentable, not merely guarded (it would upload the whole library).
    // What is REMEMBERED is the preset, not an instant — the instant is derived fresh from the phase on
    // every composition, sidestepping the seeding bug a `remember`-ed instant had (this screen mounts at
    // `Loading`, before the details fetch, so a first-composition seed captured `now` and never re-ran).
    var chosenPreset by remember { mutableStateOf(CutoffChoice.EVENT_START) }
    // The custom cutoff's local wall-clock value; null until the guest opens the picker (it then defaults
    // to the floor). Coerced up to the floor before it is ever displayed or committed.
    var customValue by remember { mutableStateOf<LocalDateTime?>(null) }
    var chosenSaveToAlbum by remember { mutableStateOf(false) }

    // The event's start, as a local wall-clock value. Non-null on Ready (the host guarantees `startsAt`);
    // a screen mounted straight into CommitFailed falls back to now, which is inert there — that phase
    // renders no cutoff row, and its Retry re-sends the cutoff the Ready phase already committed.
    val eventStart: LocalDateTime =
        phase.startsAt()?.let { cutoff.toLocal(it) } ?: cutoff.nowLocal()
    val nowLocal: LocalDateTime = cutoff.nowLocal()

    // Pre-start, "Now" would clamp to the very same instant as "Event start" (`max(now, startsAt) ==
    // startsAt`), so it is offered disabled rather than as a button that visibly does nothing.
    val eventHasStarted: Boolean = cutoff.toCutoff(eventStart) <= cutoff.nowCutoff()

    // The custom value coerced up to the floor. The backend silently raises anything earlier than the
    // event start to it (`max(chosen, startsAt)`), so we apply the same `max` before this value is ever
    // shown or sent — the screen never displays a cutoff the backend would overrule.
    val customResolved: LocalDateTime =
        (customValue ?: eventStart).let { if (it < eventStart) eventStart else it }

    // What the member is actually committing to. `JoinEvent` clamps this to `max(chosen, startsAt)` on the
    // far side; EVENT_START (and NOW before the event has started, and any coerced CUSTOM) already land on
    // or above the floor.
    val resulting: LocalDateTime = when {
        chosenPreset == CutoffChoice.CUSTOM -> customResolved
        chosenPreset == CutoffChoice.EVENT_START || !eventHasStarted -> eventStart
        else -> nowLocal
    }
    val chosenCutoff: String = cutoff.toCutoff(resulting)

    // Direction is derived from the switches. The dead (both-off) case never reaches a commit — Join is
    // disabled there — so its value is inert; DownloadOnly is an arbitrary safe placeholder.
    val chosenDirection: Direction = when {
        shareOn && receiveOn -> Direction.Both
        shareOn -> Direction.UploadOnly
        else -> Direction.DownloadOnly
    }
    val joinEnabled: Boolean = shareOn || receiveOn

    // Ready is the one phase that is a *decision surface* rather than a status-plus-actions surface, so
    // it owns its own layout (see `ReadyLayout`) instead of being squeezed into the centered-hero shape
    // every other phase shares.
    if (phase is JoinPhase.Ready) {
        ReadyLayout(
            eventName = phase.name,
            shareOn = shareOn,
            onShareOn = { shareOn = it },
            receiveOn = receiveOn,
            onReceiveOn = { receiveOn = it },
            preset = chosenPreset,
            onPreset = { chosenPreset = it },
            resulting = resulting,
            nowAvailable = eventHasStarted,
            // The DISPLAYED custom value is the resolved one (floored, defaulting to the event start), so
            // the inline field agrees with the bold instant above and never shows a date below the floor.
            customValue = customResolved,
            onCustomChange = { customValue = it },
            floor = eventStart,
            saveToAlbum = chosenSaveToAlbum,
            onSaveToAlbum = { chosenSaveToAlbum = it },
            joinEnabled = joinEnabled,
            onJoin = { onConfirm(chosenCutoff, chosenDirection, chosenSaveToAlbum) },
            onCancel = onCancel,
            chosenCutoff = chosenCutoff,
            shareableCount = shareableCount,
            photoPermission = photoPermission,
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // The body. Four of these phases carry the event's identity (ExplainAccess, Committing,
        // CommitFailed — and Loading optimistically), so they lead with the same top-anchored invitation
        // hero the Ready surface does: the badge and eyebrow never move across Loading → ExplainAccess →
        // Ready → Committing, only the name resolves and the body beneath it changes. The two pre-details
        // errors (NotFound, LoadFailed) carry no event — there is nothing to be invited to — so they show a
        // neutral notice centered on its own, never a false invitation.
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (phase) {
                // Optimistic loading: the invitation hero with the name still a placeholder, and a calm
                // spinner filling the space below. Resolves into ExplainAccess/Ready with no header jump.
                JoinPhase.Loading -> {
                    AppInvitationHeaderLoading(subtitle = JOIN_HERO_SUBTITLE)
                    CenteredBody { AppJoinProgress("Loading event details …") }
                }
                // The photo-access explainer, ahead of the confirm and ahead of the system dialog
                // (capability `join-event`). It names the event it is inviting you to (the hero) and
                // states the consent facts as a scannable card, top-anchored beneath the hero:
                // share-first (the automatic sharing is the half that deserves informed consent, so it
                // leads), then that full access is genuinely needed for BOTH halves, then that limited
                // ("pick which photos") is a first-class choice (capability `limited-photo-access`, not
                // a degraded one), then the cutoff.
                is JoinPhase.ExplainAccess -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AppEventHeaderCompact(title = phase.name, subtitle = JOIN_HERO_SUBTITLE)
                        AppSummaryCard(title = "What joining does") {
                            AppAccessPoint(
                                icon = JoinAccessShare,
                                title = "Your photos are shared automatically",
                                body = "The photos you take show up for everyone in the event.",
                                divider = false,
                            )
                            AppAccessPoint(
                                icon = JoinAccessLibrary,
                                title = "SnapSync needs your photo library",
                                body = "To share yours, and to save the photos other members send you.",
                            )
                            AppAccessPoint(
                                icon = JoinAccessChoose,
                                title = "Allow all photos, or pick which to share",
                                body = "Choosing specific photos works too — and you can add more anytime.",
                            )
                            AppAccessPoint(
                                icon = JoinAccessCutoff,
                                title = "Only photos after the date you choose",
                                body = "You pick that date on the next screen — nothing older is shared.",
                            )
                        }
                    }
                }
                // Ready renders through `ReadyLayout` above — it is not a hero-plus-actions phase.
                is JoinPhase.Ready -> Unit
                // A dead end — the event does not exist, so no invitation hero, just an honest notice.
                JoinPhase.NotFound -> CenteredBody {
                    AppNoticeCard(
                        icon = JoinNoticeInvalid,
                        title = "Invalid invite",
                        body = "This invite is invalid or the event no longer exists.",
                    )
                }
                // Transient — the event may well exist; the fetch just failed. Retryable.
                JoinPhase.LoadFailed -> CenteredBody {
                    AppNoticeCard(
                        icon = JoinNoticeOffline,
                        title = "Couldn't load the event",
                        body = "Check your connection and try again.",
                    )
                }
                // We know the event (name carried), so keep the hero pinned and show calm progress.
                is JoinPhase.Committing -> {
                    AppEventHeaderCompact(title = phase.name, subtitle = JOIN_HERO_SUBTITLE)
                    CenteredBody { AppJoinProgress("Joining …") }
                }
                // The join failed after we loaded the event, so the invitation stays honest above a
                // neutral retryable notice — no teleport back from Committing.
                is JoinPhase.CommitFailed -> {
                    AppEventHeaderCompact(title = phase.name, subtitle = JOIN_HERO_SUBTITLE)
                    CenteredBody {
                        AppNoticeCard(
                            icon = JoinNoticeFailed,
                            title = "Couldn't join",
                            body = "Something went wrong. Try again.",
                        )
                    }
                }
            }
        }
        // Actions pinned to the bottom; which ones depend on the phase.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (phase) {
                // "I understand" is the ONLY path from the join gate to the system dialog (CTA-only
                // priming). Cancel is the same cancel every other phase pins — it abandons the join.
                is JoinPhase.ExplainAccess -> {
                    PrimaryButton(label = "I understand", onClick = onAcknowledgeAccess)
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                }
                // Ready is laid out by `ReadyLayout`, above.
                is JoinPhase.Ready -> Unit
                JoinPhase.LoadFailed -> {
                    PrimaryButton(label = "Retry", onClick = onRetryLoad)
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                }
                is JoinPhase.CommitFailed -> {
                    PrimaryButton(
                        label = "Retry",
                        onClick = { onRetryJoin(chosenCutoff, chosenDirection, chosenSaveToAlbum) },
                    )
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                }
                JoinPhase.NotFound ->
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                // In-flight phases offer no actions.
                JoinPhase.Loading, is JoinPhase.Committing -> Unit
            }
        }
    }
}

/**
 * The remaining vertical space of a phase body, with its content centered. Used by the phases whose body
 * is a single calm block — a spinner or a notice card — beneath (or instead of) the invitation hero.
 */
@Composable
private fun ColumnScope.CenteredBody(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

/**
 * The **Ready** join surface: identity, then two stacked sections that each state, in words, one
 * consequence of joining — and Join / Cancel pinned at the bottom.
 *
 * The surface no longer asks "how do you want to take part?" and no longer offers a direction selector.
 * The two things a guest actually decides are stated as plain on/off switches — **Share my photos** and
 * **Receive everyone's photos** — and the participation *direction* is DERIVED from them
 * (share+receive → Both, share only → upload-only, receive only → download-only). There is deliberately no
 * "no photos" option: not sharing is the share switch off, not receiving is the receive switch off.
 *
 * The two sections, top to bottom:
 *  1. **Share** ([AppToggleSection]) — the switch, the origin-exclusions note (what the app already
 *     filters out of a camera roll — new information no other state of this screen carries), the resulting
 *     cutoff instant in the heaviest type on the surface, and the cutoff choice rows ([AppCutoffChoices]:
 *     Now / Event start / Custom) — one card, because "do I share" and "from when" are one decision.
 *     Custom opens the floored date+time picker dialog directly; only its OK commits the choice, and the
 *     chosen instant appears solely in the bold "Shared from …" line (never repeated in the row). When
 *     off, the card states that nothing of theirs leaves the phone and the rows are not shown.
 *  2. **Receive** ([AppToggleSection]) — the switch and where arriving photos land.
 *  3. **Album** ([AppMinorSection] + [AppSummaryToggle]) — a standalone second-level checkmark row: per
 *     capability `event-album` the album mirrors what the membership syncs in its direction — foreign
 *     downloads and/or the member's OWN uploads — so it belongs to neither switch, but it ranks below
 *     both (a preference, not a consent decision). Its note names exactly the feeds the current
 *     switches produce.
 *
 * Reading order is causal: who invited me → what I share (and from when) → what I receive (and where).
 *
 * Both switches off is a membership that does nothing. Rather than silently flip one switch the guest did
 * not touch, Join is **disabled** with the reason stated right above it.
 *
 * The body scrolls beneath the pinned actions: its height is not fixed (the cutoff section appears and
 * disappears, and Custom unfolds a picker), and clipping the primary action is never an acceptable way to
 * absorb that.
 */
@Composable
private fun ReadyLayout(
    eventName: String,
    shareOn: Boolean,
    onShareOn: (Boolean) -> Unit,
    receiveOn: Boolean,
    onReceiveOn: (Boolean) -> Unit,
    preset: CutoffChoice,
    onPreset: (CutoffChoice) -> Unit,
    resulting: LocalDateTime,
    nowAvailable: Boolean,
    customValue: LocalDateTime?,
    onCustomChange: (LocalDateTime) -> Unit,
    floor: LocalDateTime,
    saveToAlbum: Boolean,
    onSaveToAlbum: (Boolean) -> Unit,
    joinEnabled: Boolean,
    onJoin: () -> Unit,
    onCancel: () -> Unit,
    // The UTC `…Z` cutoff the switches+preset currently resolve to, and the permission-aware count query
    // over it (capability `join-share-count`). [photoPermission] is a recompute trigger only.
    chosenCutoff: String,
    shareableCount: suspend (cutoff: String) -> Int?,
    photoPermission: PermissionStatus,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppEventHeaderCompact(
                title = eventName,
                // The one warm line the surface allows itself — the eyebrow above already says
                // "you're invited", so this states what the invitation IS.
                subtitle = "Everyone's photos, one shared place.",
            )

            // SECTION 1 — Share: the switch header, the origin-exclusions note (kept verbatim), the
            // resulting cutoff instant in bold, and the cutoff choice rows — ONE card. The cutoff was
            // briefly its own titled section; that implied a third question where there are only two
            // ("do I share" and "do I receive"), so the rows folded back into the section whose switch
            // they refine.
            AppToggleSection(
                title = "Share my photos",
                checked = shareOn,
                onCheckedChange = onShareOn,
            ) {
                if (shareOn) {
                    // The origin exclusions (capability `photo-selection-policy`), stated as what is
                    // SUBTRACTED, never as a guarantee of what gets through: the policy cannot infer
                    // capture-origin (PhotoKit exposes no camera flag), so it removes only what is
                    // certainly not a capture and ADMITS ON DOUBT. "Screenshots … are never shared" is
                    // exactly true; "only photos you took are shared" would not be.
                    AppSectionNote(
                        "Screenshots, screen recordings, GIFs and pictures saved from chat apps are " +
                            "never shared.",
                    )
                    // The ONE statement of the instant that decides which photos leave the phone, in the
                    // heaviest type the surface renders. The Custom row below deliberately never repeats
                    // it — its picker feeds this line.
                    AppSectionValue("Shared from ${appDateTimeLabel(resulting)}")
                    // The live shareable count (capability `join-share-count`): how many of the member's
                    // own gallery photos this cutoff would share, recomputed as the cutoff (or a late
                    // permission resolve) changes. Omitted when no count is available.
                    ShareCountRow(
                        chosenCutoff = chosenCutoff,
                        shareableCount = shareableCount,
                        permissionKey = photoPermission,
                    )
                    // Level 2: the cutoff choices, in the section's recessed well. Switch = does this
                    // section happen; checkmarks = how.
                    AppSubSection {
                        AppCutoffChoices(
                            selected = preset,
                            onSelect = onPreset,
                            // Pre-start, "Now" clamps to the same instant as "Event start" — offered
                            // disabled.
                            nowAvailable = nowAvailable,
                            customValue = customValue,
                            // Only the picker's OK selects CUSTOM — a cancelled dialog leaves the
                            // previous choice (and its instant) exactly as it was.
                            onCustomPicked = {
                                onCustomChange(it)
                                onPreset(CutoffChoice.CUSTOM)
                            },
                            // The floor: the backend silently raises anything earlier to the event
                            // start, so the picker enforces it and the Custom row's note states it.
                            minimum = floor,
                            floorNote = "Can't be earlier than the event started, " +
                                "${appDateTimeLabel(floor)}.",
                        )
                    }
                } else {
                    AppSectionNote("Nothing of yours leaves this phone.")
                }
            }

            // SECTION 3 — Receive. The switch header, where photos land, and the album opt-in nested under
            // it (only while receiving). Titled to name the SOURCE ("everyone's photos"), not "save … to
            // your library" — the latter reads as backing up YOUR photos, the exact mental model this app
            // must avoid, and breaks pronoun parity with "Share my photos".
            AppToggleSection(
                title = "Receive everyone's photos",
                checked = receiveOn,
                onCheckedChange = onReceiveOn,
            ) {
                if (receiveOn) {
                    AppSectionNote("Photos others share arrive in your library automatically.")
                } else {
                    AppSectionNote("You won't receive the event's photos.")
                }
            }

            // The album (capability `event-album`) — a MINOR section: second-level checkmark idiom,
            // standalone. It can nest under neither switch (the spec feeds the album from BOTH the
            // member's own uploads and foreign downloads, so under Receive it was a false statement),
            // but a switch section of its own gave a minor preference the same weight as a consent
            // decision. The note names exactly which feeds apply to the current switches, so it can
            // never claim a feed the membership doesn't have.
            AppMinorSection {
                AppSummaryToggle(
                    label = "Create an album",
                    checked = saveToAlbum,
                    onCheckedChange = onSaveToAlbum,
                    note = when {
                        !saveToAlbum -> "No album is created."
                        shareOn && receiveOn ->
                            "Photos you share and photos you receive are collected in an album " +
                                "named after the event."
                        shareOn -> "Photos you share are collected in an album named after the event."
                        receiveOn -> "Photos you receive are collected in an album named after the event."
                        // Both switches off: nothing syncs, so nothing feeds the album. Join is already
                        // disabled with its own reason; this line keeps the row honest meanwhile.
                        else -> "Nothing is shared or received, so nothing is collected."
                    },
                    divider = false,
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Both switches off is a membership that does nothing. Say why Join is unavailable rather than
            // moving a switch the guest didn't touch.
            if (!joinEnabled) {
                StatusHint(
                    "Turn on sharing or receiving — a membership that does neither does nothing.",
                )
            }
            PrimaryButton(label = "Join", onClick = onJoin, enabled = joinEnabled)
            SecondaryButton(label = "Cancel", onClick = onCancel)
        }
    }
}

/** The live state of the shareable-count row (capability `join-share-count`). */
private sealed interface CountState {
    /** The count is being (re)computed — the row shows `counting…`. */
    data object Counting : CountState

    /** No count is available (DENIED / unresolved grant) — the row is omitted entirely. */
    data object Unavailable : CountState

    /** The count resolved to [count] photos. */
    data class Ready(val count: Int) : CountState
}

/**
 * The shareable-count row (capability `join-share-count`): `XX photos from your gallery will be shared`,
 * recomputed whenever the resolved [chosenCutoff] changes (the member tunes the cutoff) or [permissionKey]
 * flips (a late first-join grant resolves). A brief `counting…` shows while it recomputes; a zero carries a
 * forward gloss so it does not read as broken; an unavailable count (no usable grant) renders **nothing**.
 *
 * Shared by the join, switch, and reconfigure surfaces. It is a rendering concern living entirely in the
 * screen — [shareableCount] is the permission-aware, no-network query built in `compose/`.
 */
@Composable
private fun ShareCountRow(
    chosenCutoff: String,
    shareableCount: suspend (cutoff: String) -> Int?,
    permissionKey: PermissionStatus,
) {
    var state by remember { mutableStateOf<CountState>(CountState.Counting) }
    LaunchedEffect(chosenCutoff, permissionKey) {
        state = CountState.Counting
        val n = shareableCount(chosenCutoff)
        state = if (n == null) CountState.Unavailable else CountState.Ready(n)
    }
    when (val s = state) {
        CountState.Counting -> AppSectionNote("Counting your photos…")
        CountState.Unavailable -> Unit // no row without a usable photo grant
        is CountState.Ready -> {
            val noun = if (s.count == 1) "photo" else "photos"
            AppSectionNote("${s.count} $noun from your gallery will be shared")
            if (s.count == 0) {
                AppSectionNote("New photos you take will be shared as you go")
            }
        }
    }
}

/**
 * The shareable count as a single sentence for the compact switch dialog (capability `join-share-count`):
 * empty until it resolves and whenever no count is available, so the dialog body reads cleanly meanwhile.
 */
@Composable
private fun shareCountSentence(
    cutoffValue: String,
    shareableCount: suspend (cutoff: String) -> Int?,
    permissionKey: PermissionStatus,
): String {
    val count by produceState<Int?>(initialValue = null, cutoffValue, permissionKey) {
        value = shareableCount(cutoffValue)
    }
    return when (val c = count) {
        null -> ""
        0 -> "No photos from your gallery will be shared yet."
        1 -> "1 photo from your gallery will be shared."
        else -> "$c photos from your gallery will be shared."
    }
}

/**
 * The **reconfigure** surface (capability `reconfigure-membership`): a joined member re-opens the three
 * participation settings they picked at join — the two switches (Share / Receive → direction), the
 * capture-date cutoff, and the album opt-in — and changes them **in place**, without leaving.
 *
 * It reuses the exact join controls ([AppToggleSection], [AppCutoffChoices], [AppMinorSection]) so there
 * is one decision surface, differing only in that it is **pre-filled** from the current [membership] and
 * commits with **Save** (not Join) beneath a read-only event-name header.
 *
 * The cutoff preset is **reconstructed** from the persisted value, which is lossy by construction: the
 * join UI's presets are not persisted, only the resulting instant, so `minPhotoDate == startsAt` seeds
 * **Event start** and anything above it seeds **Custom** — the original "Now" pick is unrecoverable
 * (design decision "cutoff pre-fill reconstruction"). The chosen cutoff is re-clamped to the `startsAt`
 * floor on the far side, in `ReconfigureEvent`.
 *
 * Consequences are surfaced as **inline helper text**, never a blocking dialog (Save is the confirmation):
 * turning the album on states it is forward-only (no backfill), and a standing line states that a change
 * never retracts photos already shared or received. Both switches off disables Save with a stated reason,
 * exactly as the join surface disables Join.
 */
@Composable
private fun ReconfigureScreen(
    membership: EventConfig,
    cutoff: CutoffFormatter,
    shareableCount: suspend (cutoff: String) -> Int?,
    photoPermission: PermissionStatus,
    onSave: (String, Direction, String, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var shareOn by remember { mutableStateOf(membership.direction.includesUpload) }
    var receiveOn by remember { mutableStateOf(membership.direction.includesDownload) }
    // Reconstruct the preset from the persisted cutoff: at the floor → Event start; above it → Custom.
    val seededAtFloor = membership.minPhotoDate == membership.startsAt
    var chosenPreset by remember {
        mutableStateOf(if (seededAtFloor) CutoffChoice.EVENT_START else CutoffChoice.CUSTOM)
    }
    var customValue by remember {
        mutableStateOf(if (seededAtFloor) null else cutoff.toLocal(membership.minPhotoDate))
    }
    var chosenSaveToAlbum by remember { mutableStateOf(membership.saveToAlbum) }

    // Same derivation as the join surface (`JoiningEventScreen`), seeded from the membership's `startsAt`.
    val eventStart: LocalDateTime = cutoff.toLocal(membership.startsAt) ?: cutoff.nowLocal()
    val nowLocal: LocalDateTime = cutoff.nowLocal()
    val eventHasStarted: Boolean = cutoff.toCutoff(eventStart) <= cutoff.nowCutoff()
    val customResolved: LocalDateTime =
        (customValue ?: eventStart).let { if (it < eventStart) eventStart else it }
    val resulting: LocalDateTime = when {
        chosenPreset == CutoffChoice.CUSTOM -> customResolved
        chosenPreset == CutoffChoice.EVENT_START || !eventHasStarted -> eventStart
        else -> nowLocal
    }
    val chosenCutoff: String = cutoff.toCutoff(resulting)
    val chosenDirection: Direction = when {
        shareOn && receiveOn -> Direction.Both
        shareOn -> Direction.UploadOnly
        else -> Direction.DownloadOnly
    }
    val saveEnabled: Boolean = shareOn || receiveOn

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Read-only header: which event's settings these are.
            AppEventHeaderCompact(title = membership.name, subtitle = "Event settings")

            AppToggleSection(
                title = "Share my photos",
                checked = shareOn,
                onCheckedChange = { shareOn = it },
            ) {
                if (shareOn) {
                    AppSectionNote(
                        "Screenshots, screen recordings, GIFs and pictures saved from chat apps are " +
                            "never shared.",
                    )
                    AppSectionValue("Shared from ${appDateTimeLabel(resulting)}")
                    // The live count — truthful on both tiers now that a cutoff-lowering reconfigure
                    // re-shares the newly-in-scope older photos (capability `reconfigure-membership`).
                    ShareCountRow(
                        chosenCutoff = chosenCutoff,
                        shareableCount = shareableCount,
                        permissionKey = photoPermission,
                    )
                    AppSubSection {
                        AppCutoffChoices(
                            selected = chosenPreset,
                            onSelect = { chosenPreset = it },
                            nowAvailable = eventHasStarted,
                            customValue = customResolved,
                            onCustomPicked = {
                                customValue = it
                                chosenPreset = CutoffChoice.CUSTOM
                            },
                            minimum = eventStart,
                            floorNote = "Can't be earlier than the event started, " +
                                "${appDateTimeLabel(eventStart)}.",
                        )
                    }
                } else {
                    AppSectionNote("Nothing of yours leaves this phone.")
                }
            }

            AppToggleSection(
                title = "Receive everyone's photos",
                checked = receiveOn,
                onCheckedChange = { receiveOn = it },
            ) {
                if (receiveOn) {
                    AppSectionNote("Photos others share arrive in your library automatically.")
                } else {
                    AppSectionNote("You won't receive the event's photos.")
                }
            }

            AppMinorSection {
                AppSummaryToggle(
                    label = "Create an album",
                    checked = chosenSaveToAlbum,
                    onCheckedChange = { chosenSaveToAlbum = it },
                    // Forward-only (capability `reconfigure-membership`): already-synced photos are not
                    // retroactively gathered, so the on-note says so plainly.
                    note = if (chosenSaveToAlbum) {
                        "Photos are collected in an album named after the event. Only photos synced " +
                            "from now on are added."
                    } else {
                        "No album is created."
                    },
                    divider = false,
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Standing consequence line: a change is forward-only and never retracts what already synced.
            StatusHint("Changes apply from now on — photos already shared or received stay.")
            if (!saveEnabled) {
                StatusHint(
                    "Turn on sharing or receiving — a membership that does neither does nothing.",
                )
            }
            PrimaryButton(
                label = "Save",
                onClick = { onSave(membership.eventId, chosenDirection, chosenCutoff, chosenSaveToAlbum) },
                enabled = saveEnabled,
            )
            SecondaryButton(label = "Cancel", onClick = onCancel)
        }
    }
}

/**
 * The switch confirmation (a different event scanned while joined) — the leave-style dialog. On confirm
 * it runs leave-then-join. Mirrors the join phases in a compact `AppConfirmDialog`: the loaded phase
 * offers Switch; a load/commit failure offers Retry; a missing event dismisses. Transient
 * loading/committing phases show nothing.
 */
@Composable
private fun SwitchDialog(
    switch: PendingSwitch,
    currentEventName: String?,
    onConfirmSwitch: (String, Direction) -> Unit,
    onCancelSwitch: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryJoin: (String, Direction) -> Unit,
    shareableCount: suspend (cutoff: String) -> Int? = { null },
    photoPermission: PermissionStatus = PermissionStatus.GRANTED,
) {
    val current = currentEventName ?: "this event"
    // The compact switch dialog has no picker: it uses the new event's default cutoff — its `startsAt`,
    // which is also the FLOOR, so the switch lands exactly on it (capability `photo-selection-policy`) — and the
    // default participation direction ([Direction.Both]).
    // Remembered so a retry after a failed commit reuses it (the CommitFailed phase carries name+startsAt).
    var cutoff by remember { mutableStateOf<String?>(null) }
    when (val phase = switch.phase) {
        // Unreachable. The photo-access explainer is a FIRST-join surface: `readyOrExplain` emits it only
        // when `config == null`, and a switch by definition has a config. Anyone switching is already on the
        // joined layer, where the `NeedsAccess` affordance handles a missing grant — so no explanation is
        // lost. Kotlin's exhaustive `when` requires the branch; the container test "a switch never explains"
        // is what keeps it dead (capability `join-event`).
        is JoinPhase.ExplainAccess -> Unit
        is JoinPhase.Ready -> {
            cutoff = phase.startsAt
            // The shareable count for the switch's fixed cutoff (the new event's start). Appended to the
            // body as a sentence — the compact dialog has no room for the join surface's own row. Empty
            // until it resolves (and when no count is available), so the dialog reads cleanly meanwhile.
            val countSentence = shareCountSentence(phase.startsAt, shareableCount, photoPermission)
            AppDestructiveConfirmDialog(
                title = "Switch events?",
                // The names carry the whole weight of the decision, so they lead the body line; the
                // title is the crisp question. Destructive, because leaving is irreversible. The second
                // sentence states the participation the switch silently resets to (spec-pinned: a switch
                // joins with direction Both, cutoff = event start, album off) so it is not a surprise.
                body = ("You'll leave \"$current\" and join \"${phase.name}\". " +
                    "You'll share photos you take and receive everyone's. $countSentence").trim(),
                confirmLabel = "Switch",
                cancelLabel = "Cancel",
                onConfirm = { onConfirmSwitch(phase.startsAt, Direction.Both) },
                onDismiss = onCancelSwitch,
            )
        }
        JoinPhase.NotFound ->
            AppConfirmDialog(
                title = "Invite not found",
                body = "This invite is invalid or the event no longer exists.",
                confirmLabel = "OK",
                cancelLabel = "Cancel",
                onConfirm = onCancelSwitch,
                onDismiss = onCancelSwitch,
            )
        JoinPhase.LoadFailed ->
            AppConfirmDialog(
                title = "Couldn't load the event",
                body = "Check your connection and try again.",
                confirmLabel = "Retry",
                cancelLabel = "Cancel",
                onConfirm = onRetryLoad,
                onDismiss = onCancelSwitch,
            )
        is JoinPhase.CommitFailed ->
            AppConfirmDialog(
                title = "Couldn't switch events",
                body = "Something went wrong. Try again.",
                confirmLabel = "Retry",
                cancelLabel = "Cancel",
                // The remembered cutoff was set by the Ready phase this commit came from; a retry without
                // one would join at whole-library scope, so it is inert rather than unbounded.
                onConfirm = { cutoff?.let { c -> onRetryJoin(c, Direction.Both) } },
                onDismiss = onCancelSwitch,
            )
        // Transient — no dialog while the details load or the switch commits.
        JoinPhase.Loading, is JoinPhase.Committing -> Unit
    }
}

/**
 * The joined-layer event home: the join QR is the hero, the one-line sync health beneath it (the event
 * name is the screen heading above, per [ScreenLayout]). The permission affordance is folded into the
 * status line (the `NeedsAccess` variant), tappable to the right action — never a hero-replacing gate.
 */
@Composable
private fun JoinedLayer(
    health: SyncHealth,
    inviteUrl: String?,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    canChoosePhotos: Boolean,
    onChoosePhotos: () -> Unit,
    cutoff: CutoffFormatter,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // The invite hero: sharing the event IS the point, so the QR is the tallest object on the screen.
        // A tracked accent eyebrow names what the code is FOR to the current member (share it to add
        // guests), while the card's own caption instructs the person scanning it — two audiences, one
        // statement each, so neither line repeats the other.
        if (inviteUrl != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppEyebrow("Share this event", EyebrowTone.Accent)
                AppQrCode(content = inviteUrl, caption = "Scan to join this event")
            }
        }
        // The one sync-health line — bare, no card. It briefly wore a surface-filled panel, but a white
        // card under a white QR card read as a second competing surface; the screen's second fixation
        // needs no frame, just position (centered, beneath the code).
        AppStatusLine(
            status = health.toAppSyncStatus(cutoff),
            onAttentionClick = {
                if (health is SyncHealth.NeedsAccess) {
                    if (health.permission == PermissionStatus.NOT_DETERMINED) {
                        onRequestPermission()
                    } else {
                        onOpenSettings()
                    }
                }
            },
        )
        // The partial-grant resting affordances (capability `limited-photo-access`): present in every
        // health, OUTSIDE the status-line slot — the selection is the membership's scope, and widening
        // it is an ordinary action, not a problem to fix. Two peer offers in fixed order: widen the
        // selection (the cheaper step) above, switch the grant itself below. The second can only
        // deep-link to Settings — no API re-raises the full-access dialog under a limited grant — and
        // deliberately carries no interstitial consent: the label plus the OS-mediated toggle are the
        // consent, and the widened scope stays bounded by the selection policy like any full grant.
        if (canChoosePhotos) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SecondaryButton(label = "Choose more photos", onClick = onChoosePhotos)
                SecondaryButton(label = "Allow full access", onClick = onOpenSettings)
            }
        }
    }
}

private fun SyncHealth.toAppSyncStatus(cutoff: CutoffFormatter): AppSyncStatus = when (this) {
    is SyncHealth.NeedsAccess -> AppSyncStatus.NeedsAccess(
        if (permission == PermissionStatus.NOT_DETERMINED) AccessPrompt.ALLOW else AccessPrompt.SETTINGS,
    )
    // The clock line renders the start in the DEVICE's local zone — a guest in another timezone sees the
    // event begin at their own wall-clock time, which is the honest reading of an instant. An unparseable
    // startsAt cannot occur (the details source normalizes it, and the config decoder requires it), so an
    // unreadable one degrades to the neutral first frame rather than crashing the joined screen.
    is SyncHealth.NotStarted ->
        cutoff.toLocal(startsAt)?.let { AppSyncStatus.NotStarted(it) } ?: AppSyncStatus.Loading
    SyncHealth.Unattested -> AppSyncStatus.CannotVerifyDevice
    SyncHealth.Loading -> AppSyncStatus.Loading
    SyncHealth.InSync -> AppSyncStatus.InSync
    // Since the step-9 Arrow/ArrowLevel unification both sides speak `model/`'s Arrow — no mapping.
    is SyncHealth.Syncing -> AppSyncStatus.Syncing(upload, download)
}

/**
 * The create-event landing layer (event-creation-ui) — the app's front door for a HOST, brought to the
 * same design language as the join gate. It reads as an invitation being *authored*: the compact host
 * header (the real app mark + "HOST AN EVENT" eyebrow + title + one warm line) leads, then the one
 * question the surface asks — what is it called — with the name field answering it, then the event start
 * as a stated-consequence card. Create + the scan hint stay pinned to the bottom.
 *
 * The header is the compact (left-aligned) form so identity costs one line-pair: the short form below —
 * and the transient "creating …" state that replaces it ([CreatingEventScreen]) — stay anchored in the
 * same place, so the surface never jumps between the two.
 *
 * The name and the start live in local Compose state (only the submitted values cross the container);
 * Create is disabled until the trimmed name is non-empty, and the field caps at 100 characters — so a
 * returned failure is a *submission* failure (the server was unreachable or rejected it), not the current
 * name being malformed. It is therefore stated in an [AppErrorBanner] above the action, never as a red
 * field, which would falsely blame the host's typing.
 *
 * The start defaults to **now, frozen at first composition** (`remember { … }`, not re-derived at submit).
 * The label is the screen's whole statement about what will be sent, so a value that silently drifted
 * between being displayed and being posted would make the screen lie. A slow typer therefore sets a start
 * a few minutes in the past — harmless, since they are at their own event.
 */
@Composable
private fun CreateEventScreen(
    state: UiState.CreateEvent,
    onCreateEvent: (String, LocalDateTime) -> Unit,
    transientError: String?,
    cutoff: CutoffFormatter,
) {
    var name by remember { mutableStateOf("") }
    var startsAt by remember { mutableStateOf(cutoff.nowLocal()) }
    // A returned failure — a scanned-invalid-link (transient) or a creation failure reduced into
    // `state.error` — is a submission-level condition, not a live field error, so it is banished to a
    // banner above the action rather than reddening the name field.
    val bannerError: String? = transientError ?: state.error
    Column(modifier = Modifier.fillMaxSize()) {
        // Identity, pinned to the top so it holds its place across the form / creating swap.
        AppEventHeaderHost(
            title = "Start an event",
            subtitle = "Everyone's photos, one shared place.",
        )
        // The form flows directly beneath the header that introduces it (the join gate's top-aligned
        // grammar), scrolling under the pinned action. Grouping the header with its form reads more
        // coherently than floating the form in the middle would.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppQuestionHeading("What's it called?")
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Event name",
                    maxLength = EVENT_NAME_MAX_LENGTH,
                )
            }
            AppEventStartSection(
                value = startsAt,
                onValueChange = { startsAt = it },
                // The truthfulness line: this start is the floor of every guest's capture-date cutoff
                // (capability `photo-selection-policy`) — stated once, where it is set.
                note = "Only photos taken after this time are shared — the earliest cutoff any guest can pick.",
            )
        }
        // Action pinned to the bottom.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (bannerError != null) {
                AppErrorBanner(bannerError)
            }
            PrimaryButton(
                label = "Create event",
                onClick = { onCreateEvent(name, startsAt) },
                enabled = name.isNotBlank(),
            )
            StatusHint("Or scan a QR code in the Camera app to join one.")
        }
    }
}

/**
 * The in-flight create state (event-creation-ui): the SAME host header as the form, held in the SAME
 * top-anchored place, with a calm centered spinner where the form was. Keeping the header put is what
 * makes this read as the form *settling* rather than a new screen — no layout jump.
 */
@Composable
private fun CreatingEventScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        AppEventHeaderHost(
            title = "Start an event",
            subtitle = "Everyone's photos, one shared place.",
        )
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusHero(StatusIndicator.Loading, "Creating your event …")
        }
    }
}

// Mirrors the backend's name cap (trimmed, non-empty, ≤100) so a server 400 is near-unreachable.
private const val EVENT_NAME_MAX_LENGTH = 100
