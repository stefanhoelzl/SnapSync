package app.snapsync.ui

import app.snapsync.model.EventStart
import app.snapsync.model.EventEnd
import app.snapsync.model.DeletesAt
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.Direction
import app.snapsync.model.PermissionStatus
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.JoinPhase
import app.snapsync.ui.components.AppAccessPoint
import app.snapsync.ui.components.AppInvitationHeaderLoading
import app.snapsync.ui.components.AppJoinProgress
import app.snapsync.ui.components.AppNoticeCard
import app.snapsync.ui.components.AppSummaryCard
import app.snapsync.ui.components.JOIN_HERO_SUBTITLE
import app.snapsync.ui.components.JoinAccessChoose
import app.snapsync.ui.components.JoinAccessCutoff
import app.snapsync.ui.components.JoinAccessLibrary
import app.snapsync.ui.components.JoinAccessShare
import app.snapsync.ui.components.JoinNoticeFailed
import app.snapsync.ui.components.JoinNoticeInvalid
import app.snapsync.ui.components.JoinNoticeOffline
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import app.snapsync.ui.components.AppRangePresetChoices
import app.snapsync.ui.components.FromChoice
import app.snapsync.ui.components.UntilChoice
import app.snapsync.ui.components.AppEventHeaderCompact
import app.snapsync.ui.components.AppSectionNote
import app.snapsync.ui.components.AppMinorSection
import app.snapsync.ui.components.AppSectionValue
import app.snapsync.ui.components.AppSummaryToggle
import app.snapsync.ui.components.AppToggleSection
import app.snapsync.ui.components.appDateLabel
import app.snapsync.ui.components.appDateTimeLabel
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.SecondaryButton
import app.snapsync.ui.components.StatusHint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// The join gate (capability `join-event`): the full-screen surface a scanned link opens, its
// phase-derived accessors, and the ready-to-confirm layout with its shareable-count row.

/**
 * The event's start, from whichever phase carries it (capability `photo-selection-policy`).
 *
 * `null` on the phases that carry none (`Loading` before the fetch resolves; `NotFound`/`LoadFailed`),
 * which is why the cutoff row seeds from the first phase that *does* carry one, rather than from
 * whichever phase the screen happened to mount at.
 *
 * Unlike the seed it replaces, this covers **Committing and CommitFailed too**. Those phases carry
 * `startsAt` precisely because a Retry commits WITHOUT passing back through the loaded phase — reading it
 * only from `Ready` would make a retry derive its cutoff from `now` instead of the start the user chose,
 * silently discarding their selection at the one moment they are already recovering from a failure.
 */
internal fun JoinPhase.startsAt(): EventStart? = when (this) {
    is JoinPhase.ExplainAccess -> startsAt
    is JoinPhase.Ready -> startsAt
    is JoinPhase.Committing -> startsAt
    is JoinPhase.CommitFailed -> startsAt
    JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed -> null
}

/**
 * The event's end, from whichever phase carries it (capability `photo-selection-policy`) — the range's
 * upper **default** and its **ceiling**. Carried by the same four phases as [startsAt], and for the same
 * reason: a Retry commits without passing back through the loaded phase, so the ceiling has to still be
 * here or the retry would derive its upper bound from a phase that lost it.
 */
internal fun JoinPhase.endsAt(): EventEnd? = when (this) {
    is JoinPhase.ExplainAccess -> endsAt
    is JoinPhase.Ready -> endsAt
    is JoinPhase.Committing -> endsAt
    is JoinPhase.CommitFailed -> endsAt
    JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed -> null
}

/**
 * The event's **retention deadline**, from whichever phase carries it (capability `event-limits`) — when
 * the shared photos are deleted. Server-derived and carried verbatim; never computed here, because a
 * client-side copy of the retention rule would promise a date the backend will not honour, silently.
 *
 * Carried by the same four phases as [startsAt]/[endsAt] for the same reason — a Retry commits without
 * passing back through the loaded phase, and the commit persists this value as the offline witness of the
 * self-leave (capability `leave-event`).
 */
internal fun JoinPhase.deletesAt(): DeletesAt? = when (this) {
    is JoinPhase.ExplainAccess -> deletesAt
    is JoinPhase.Ready -> deletesAt
    is JoinPhase.Committing -> deletesAt
    is JoinPhase.CommitFailed -> deletesAt
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
internal fun JoiningEventScreen(
    phase: JoinPhase,
    cutoff: CutoffFormatter,
    onConfirm: (CaptureCutoff, CaptureCeiling, Direction, Boolean) -> Unit,
    onAcknowledgeAccess: () -> Unit,
    onCancel: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryJoin: (CaptureCutoff, CaptureCeiling, Direction, Boolean) -> Unit,
    shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int?,
    photoPermission: PermissionStatus,
) {
    // The two participation switches, both default ON. Direction is DERIVED from them, never chosen:
    // share+receive → Both, share only → UploadOnly, receive only → DownloadOnly. There is deliberately no
    // "no photos" option in either — "not sharing" IS the share switch off, "not receiving" the receive
    // switch off. Both off is representable and does nothing; Join is disabled with a stated reason rather
    // than one switch silently flipping the other.
    var shareOn by remember { mutableStateOf(true) }
    var receiveOn by remember { mutableStateOf(true) }

    // The capture-date RANGE is a pair of presets (capability `photo-selection-policy`), defaulting to the
    // FULL event window `[Event start, Event end]` (narrow, never widen — admits on doubt). What is
    // REMEMBERED is the presets and (for Custom) the picked wall-clock values, NEVER a default instant — the
    // instants are derived fresh from the phase on every composition, sidestepping the seeding bug a
    // `remember`-ed instant had (this screen mounts at `Loading`, before the details fetch, so a
    // first-composition seed captured `now` and never re-ran).
    var fromPreset by remember { mutableStateOf(FromChoice.EVENT_START) }
    var fromCustom by remember { mutableStateOf<LocalDateTime?>(null) }
    var untilPreset by remember { mutableStateOf(UntilChoice.EVENT_END) }
    var untilCustom by remember { mutableStateOf<LocalDateTime?>(null) }
    var chosenSaveToAlbum by remember { mutableStateOf(false) }

    // Range derivation from the phase's window. On Ready the host guarantees both `startsAt` and `endsAt`;
    // a screen mounted straight into a phase with neither falls back to a now
    // from-bound and an effectively-unbounded until (the safe direction), which is inert there — those
    // phases render no range row, and a retry re-sends the range the Ready phase already committed.
    // What the guest has actually chosen, resolved from the phase's window and the four choice controls
    // (see [JoinSelection]). Pulled out because it is pure derivation and answers a different question
    // from everything below it: this decides WHAT would be committed, the `when` further down decides
    // what is DRAWN.
    val selection = rememberJoinSelection(
        phase = phase,
        cutoff = cutoff,
        fromPreset = fromPreset,
        fromCustom = fromCustom,
        untilPreset = untilPreset,
        untilCustom = untilCustom,
        shareOn = shareOn,
        receiveOn = receiveOn,
    )

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
            fromPreset = fromPreset,
            onFromPreset = { fromPreset = it },
            fromCustom = fromCustom,
            onFromCustom = { fromCustom = it },
            untilPreset = untilPreset,
            onUntilPreset = { untilPreset = it },
            untilCustom = untilCustom,
            onUntilCustom = { untilCustom = it },
            rangeLabel = cutoff.formatRange(selection.fromResolved, selection.untilResolved),
            nowAvailable = selection.nowAvailable,
            windowStart = selection.windowStart,
            windowEnd = selection.windowEnd,
            floorLabel = appDateTimeLabel(selection.windowStart),
            ceilingLabel = appDateTimeLabel(selection.windowEnd),
            // The retention deadline, rendered as a plain date. Absent only if a phase somehow lost it,
            // in which case the section states the fixed ceiling alone rather than inventing a date.
            deletesLabel = phase.deletesAt()?.let { cutoff.toLocal(it.at) }?.let(::appDateLabel),
            saveToAlbum = chosenSaveToAlbum,
            onSaveToAlbum = { chosenSaveToAlbum = it },
            joinEnabled = selection.joinEnabled,
            onJoin = {
                onConfirm(
                    selection.chosenFrom, selection.chosenUntil, selection.chosenDirection, chosenSaveToAlbum,
                )
            },
            onCancel = onCancel,
            chosenFrom = selection.chosenFrom,
            chosenUntil = selection.chosenUntil,
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
                        onClick = {
                            onRetryJoin(
                                selection.chosenFrom,
                                selection.chosenUntil,
                                selection.chosenDirection,
                                chosenSaveToAlbum,
                            )
                        },
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
 *     range in the heaviest type on the surface, and the range choice rows ([AppRangePresetChoices]) as two
 *     captioned sub-lists — **Share from** (Event start / Now / Custom) and **Share until** (Event end /
 *     Custom), each in its own recessed well the component owns — all in one card, because "do I share" and
 *     "from when / until when" are one decision. Custom opens the window-constrained date+time picker
 *     directly; only its OK commits the choice, and the chosen instants appear solely in the bold
 *     "Sharing …" line (never repeated in a row). When off, the card states that nothing of theirs leaves
 *     the phone and the rows are not shown.
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
    fromPreset: FromChoice,
    onFromPreset: (FromChoice) -> Unit,
    fromCustom: LocalDateTime?,
    onFromCustom: (LocalDateTime) -> Unit,
    untilPreset: UntilChoice,
    onUntilPreset: (UntilChoice) -> Unit,
    untilCustom: LocalDateTime?,
    onUntilCustom: (LocalDateTime) -> Unit,
    // The resolved range as one readable label — the section's single statement of what will be shared.
    rangeLabel: String,
    nowAvailable: Boolean,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    floorLabel: String,
    ceilingLabel: String,
    // When the event's shared photos are deleted (capability `event-limits`), pre-formatted; `null` only
    // when the phase carries no deadline, in which case only the fixed ceiling is stated.
    deletesLabel: String?,
    saveToAlbum: Boolean,
    onSaveToAlbum: (Boolean) -> Unit,
    joinEnabled: Boolean,
    onJoin: () -> Unit,
    onCancel: () -> Unit,
    // The UTC `…Z` range bounds the switches+presets currently resolve to, and the permission-aware count
    // query over `[from, until]` (capability `join-share-count`). [photoPermission] is a recompute trigger only.
    chosenFrom: CaptureCutoff,
    chosenUntil: CaptureCeiling,
    shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int?,
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
                    // The ONE statement of the RANGE that decides which photos leave the phone, in the
                    // heaviest type the surface renders. The Custom rows below deliberately never repeat
                    // it — their pickers feed this line.
                    AppSectionValue("Sharing $rangeLabel")
                    // The live shareable count (capability `join-share-count`): how many of the member's
                    // own gallery photos this RANGE would share, recomputed as either bound (or a late
                    // permission resolve) changes. Omitted when no count is available.
                    ShareCountRow(
                        chosenCutoff = chosenFrom,
                        chosenUntil = chosenUntil,
                        shareableCount = shareableCount,
                        permissionKey = photoPermission,
                    )
                    // Level 2: the From/Until range presets, each its own captioned sub-list in its own
                    // recessed well — the component owns those wells, so this section wraps it in none.
                    // Switch = does this section happen; checkmarks = how.
                    AppRangePresetChoices(
                        fromSelected = fromPreset,
                        onFromSelect = onFromPreset,
                        fromCustomValue = fromCustom,
                        // Only the picker's OK selects CUSTOM — a cancelled dialog leaves the previous
                        // choice (and its instant) exactly as it was.
                        onFromCustomPicked = {
                            onFromCustom(it)
                            onFromPreset(FromChoice.CUSTOM)
                        },
                        untilSelected = untilPreset,
                        onUntilSelect = onUntilPreset,
                        untilCustomValue = untilCustom,
                        onUntilCustomPicked = {
                            onUntilCustom(it)
                            onUntilPreset(UntilChoice.CUSTOM)
                        },
                        // Pre-start (and post-end), "Now" would fall outside the window — offered disabled.
                        nowAvailable = nowAvailable,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        fromFloorNote = "Can't be earlier than the event started, $floorLabel.",
                        untilCeilingNote = "Can't be later than the event ends, $ceilingLabel.",
                    )
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

            // How long the shared photos are kept (capability `event-limits`). This is the ONE place the
            // app states retention — the creator passes through this same gate right after minting, so a
            // single line serves the host and every guest.
            //
            // The date is the CEILING, stated unconditionally. An event is often reclaimed sooner (once
            // everyone has left, capability `scheduled-cleanup`), but that depends on every member's leave
            // reaching the backend and is NOT assured — so it must never be presented as a promise, nor as
            // a qualification that makes this date read as unreliable.
            AppMinorSection {
                AppSectionNote(
                    buildString {
                        if (deletesLabel != null) append("Shared photos are deleted on $deletesLabel. ")
                        append("An event's photos are kept for at most 30 days from the day it starts.")
                    },
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
internal fun ShareCountRow(
    chosenCutoff: CaptureCutoff,
    chosenUntil: CaptureCeiling?,
    shareableCount: suspend (cutoff: CaptureCutoff, until: CaptureCeiling?) -> Int?,
    permissionKey: PermissionStatus,
) {
    var state by remember { mutableStateOf<CountState>(CountState.Counting) }
    LaunchedEffect(chosenCutoff, chosenUntil, permissionKey) {
        state = CountState.Counting
        val n = shareableCount(chosenCutoff, chosenUntil)
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
