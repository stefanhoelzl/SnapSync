package app.snapsync.ui

import androidx.compose.foundation.layout.Box
import app.snapsync.ui.components.AppNetworkNotice
import app.snapsync.model.NetworkNotice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.EVENT_NAME_MAX_LENGTH
import app.snapsync.model.StoreKind
import app.snapsync.model.EVENT_WINDOW_MAX_SECONDS
import kotlin.time.Duration.Companion.seconds
import app.snapsync.model.Layer
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.model.UiState
import app.snapsync.ui.components.AppEventHeaderHost
import app.snapsync.ui.components.AppIdentityHeader
import app.snapsync.ui.components.AppEventRangePicker
import app.snapsync.ui.components.EndTimeGuide
import app.snapsync.ui.components.RangeBounds
import kotlinx.datetime.LocalDateTime
import app.snapsync.ui.components.AppQuestionHeading
import app.snapsync.ui.components.AppTextField
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.StatusHero
import app.snapsync.ui.components.StatusHint
import app.snapsync.ui.components.StatusIndicator

// Event creation (capability `create-event`): the name/date form and its in-flight state.

/** The longest event window, in whole days, as the create screen states it. */
private val EVENT_WINDOW_MAX_DAYS: Long = EVENT_WINDOW_MAX_SECONDS.seconds.inWholeDays

/**
 * The create-event landing layer (create-event) — the app's front door for a HOST, brought to the
 * same design language as the join gate. It reads as an invitation being *authored*: the compact host
 * header leads, then the two questions the surface asks — what is it called, and when is it — with the name
 * field and the inline range picker answering them. Create + one line under it stay pinned to the bottom.
 *
 * The name and the range live in the [draft] (only the submitted values cross the container). The range
 * has NO complete default: the start is preset to now (following the clock until the host chooses something
 * in the range, see [CreateDraft]) and the last day to today, but the end TIME must be chosen, because a
 * pre-filled window the host never looked at was almost always wrong and silently bounds every member's
 * photos (capability `photo-sharing`). Create is disabled until [nextStep] is complete; a line ABOVE Create
 * names the next missing step — a button that leads to it: the name field, focused with the keyboard up, or
 * the end-time wheels, brought into view and outlined — and turns into the event's duration once there is
 * none. The picker cannot produce an inverted or over-long range, so the window guards in [createEnabled]
 * only restate it.
 *
 * A returned failure is a *submission* failure (the server was unreachable or rejected it), not the current
 * input being malformed. It replaces the scan hint BELOW Create — never a red field, which would blame the
 * host's typing, and never a banner that grows the pinned area over the range picker.
 */
@Composable
internal fun CreateEventScreen(
    state: Layer.CreateEvent,
    draft: CreateDraft,
    callbacks: CreateCallbacks,
    cutoff: CutoffFormatter,
) {
    val nameFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var endTimeRequests by remember { mutableIntStateOf(0) }
    val guide = StepGuide(
        toName = {
            nameFocus.requestFocus()
            keyboard?.show()
        },
        toEndTime = { endTimeRequests++ },
    )
    Column(modifier = Modifier.fillMaxSize()) {
        // Identity, pinned to the top so it holds its place across the form / creating swap.
        AppEventHeaderHost(
            title = "Create an event",
            subtitle = "Every photo, in your gallery.",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CreateQuestion("What's it called?", Modifier.padding(top = 18.dp)) {
                AppTextField(
                    value = draft.name,
                    onValueChange = { draft.name = it },
                    placeholder = "e.g. Anna's birthday",
                    maxLength = EVENT_NAME_MAX_LENGTH,
                    focusRequester = nameFocus,
                )
            }
            CreateQuestion("When is it?") {
                AppEventRangePicker(
                    range = draft.range,
                    bounds = RangeBounds.lastingAtMost(cutoff::latestEnd),
                    // The truthfulness line: this window is the event's capture-date bound (capability
                    // `photo-sharing`) — stated once, where it is set — and the one limit on it.
                    note = "Only photos taken between these dates are shared. An event can last up to " +
                        "$EVENT_WINDOW_MAX_DAYS days.",
                    currentHour = { cutoff.nowLocal().hour },
                    endTime = EndTimeGuide(showRequests = endTimeRequests, onPickEndTime = guide.toEndTime),
                    onChange = draft::choose,
                )
            }
        }
        CreateActions(state, draft, callbacks, cutoff, guide)
    }
}

/** Where tapping the named next missing step leads (capability `create-event`). */
private class StepGuide(val toName: () -> Unit, val toEndTime: () -> Unit)

/** One question the form asks, over the control that answers it. */
@Composable
private fun CreateQuestion(question: String, modifier: Modifier = Modifier, answer: @Composable () -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppQuestionHeading(question)
        answer()
    }
}

/** What the create screen can ask for: the create itself, and SnapSync's Settings page when its network is blocked. */
internal class CreateCallbacks(
    val onCreateEvent: (String, LocalDateTime, LocalDateTime) -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * The pinned bottom: what is still missing (or how long the event lasts), Create, and one line under it —
 * the scan hint, or in its place the failure, or above both a missing network (capability `create-event`), which
 * also takes Create away. A constant height, so nothing here ever covers the picker.
 */
@Composable
private fun CreateActions(
    state: Layer.CreateEvent,
    draft: CreateDraft,
    callbacks: CreateCallbacks,
    cutoff: CutoffFormatter,
    guide: StepGuide,
) {
    // ONE value: the reduction already coalesced a sticky create failure and a self-clearing invalid-link
    // notice, the transient winning, so the screen renders what it is given.
    val error: String? = state.error
    val until = draft.range.until
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val step = draft.nextStep()
        val toStep = when (step) {
            CreateStep.NAME -> guide.toName
            CreateStep.END_TIME -> guide.toEndTime
            CreateStep.COMPLETE -> null
        }
        StatusHint(nextStepLine(step, draft.range.from, until, cutoff), onClick = toStep)
        PrimaryButton(
            label = "Create event",
            onClick = { if (until != null) callbacks.onCreateEvent(draft.name, draft.range.from, until) },
            enabled = createEnabled(draft, cutoff) && state.network == null,
        )
        val network = state.network
        if (network != null) {
            // The failure an earlier attempt left is kept by the reduction and returns with the network.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AppNetworkNotice(blocked = network == NetworkNotice.BLOCKED, onOpenSettings = callbacks.onOpenSettings)
            }
        } else {
            StatusHint(error ?: "To join an event instead, scan its QR code with your camera.", isError = error != null)
        }
    }
}

/** The line above Create: the next missing step, or — once there is none — the event's duration. */
private fun nextStepLine(step: CreateStep, from: LocalDateTime, until: LocalDateTime?, cutoff: CutoffFormatter) =
    when (step) {
        CreateStep.NAME -> "Name the event"
        CreateStep.END_TIME -> "Pick an end time"
        CreateStep.COMPLETE -> until?.let { "Event lasts ${cutoff.humanizedDuration(from, it)}" }.orEmpty()
    }

/**
 * Create is enabled only for a complete draft. The window checks restate what the picker already cannot
 * produce (an inverted or over-long range), so a regression there is refused rather than submitted.
 */
private fun createEnabled(draft: CreateDraft, cutoff: CutoffFormatter): Boolean {
    val until = draft.range.until ?: return false
    return draft.nextStep() == CreateStep.COMPLETE && draft.range.from < until &&
        cutoff.fitsEventWindow(draft.range.from, until)
}

/**
 * The in-flight create state (create-event): the SAME host header as the form, held in the SAME
 * top-anchored place, with a calm centered spinner where the form was. Keeping the header put is what
 * makes this read as the form *settling* rather than a new screen — no layout jump.
 */
@Composable
internal fun CreatingEventScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        AppEventHeaderHost(
            title = "Create an event",
            subtitle = "Every photo, in your gallery.",
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



/**
 * The backend refuses this build as too old (capability `app-update-required`).
 *
 * The one screen in the app whose remedy is **outside** it, and it is built to say exactly that and
 * nothing else. There is no retry, because retrying is what the app has already been doing and every
 * attempt is refused; there is no way back to another layer, because every other layer would render
 * something untrue about a device whose every backend call is being turned away.
 *
 * [Layer.UpdateRequired.minimumVersion] is named only when the backend sent one — a refusal that
 * carried no version still shows this screen, without inventing a number. The store button appears only
 * when this build carries a store URL, for the reason the layer's own doc gives: a composed
 * country-less URL is measurably a 404 while availability is limited, and a button that lands nowhere
 * is worse than no button on the screen a member reaches because something is already wrong.
 */
@Composable
internal fun UpdateRequiredScreen(layer: Layer.UpdateRequired, onOpenLink: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Its OWN eyebrow. Reaching for `AppEventHeaderHost` here put "HOST AN EVENT" above this screen
        // on a real device — a surface borrowing another's verb, which no state assertion could see.
        AppIdentityHeader(
            eyebrow = "UPDATE NEEDED",
            title = "Update SnapSync",
            subtitle = "This version can no longer reach the event.",
        )
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Headline and DETAIL, which is the shape `StatusHero` lays out: the icon sits beside a
            // short line, with the sentence full-width beneath it. Passing the whole sentence as the
            // headline instead left the icon floating against its middle line (seen on device).
            StatusHero(
                StatusIndicator.Error,
                "Time to update",
                layer.minimumVersion
                    ?.let { "SnapSync $it or newer is needed to keep sharing photos." }
                    ?: "A newer version of SnapSync is needed to keep sharing photos.",
            )
        }
        layer.store?.let { store ->
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                PrimaryButton(label = storeButtonLabel(store.kind), onClick = { onOpenLink(store.url) })
            }
        }
    }
}

/** The update notice's one button names the store it opens — the one the build is distributed through. */
private fun storeButtonLabel(kind: StoreKind): String = when (kind) {
    StoreKind.APP_STORE -> "Open the App Store"
    StoreKind.GOOGLE_PLAY -> "Open Google Play"
}
