package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.EVENT_NAME_MAX_LENGTH
import app.snapsync.model.EVENT_WINDOW_MAX_SECONDS
import kotlin.time.Duration.Companion.seconds
import app.snapsync.model.Layer
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.model.UiState
import app.snapsync.ui.components.AppErrorBanner
import app.snapsync.ui.components.appRangeLabel
import app.snapsync.ui.components.AppEventHeaderHost
import app.snapsync.ui.components.AppIdentityHeader
import app.snapsync.ui.components.AppEventDateRangeSection
import kotlinx.datetime.LocalDateTime
import app.snapsync.ui.components.AppQuestionHeading
import app.snapsync.ui.components.AppTextField
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.StatusHero
import app.snapsync.ui.components.StatusHint
import app.snapsync.ui.components.StatusIndicator

// Event creation (capability `create-event`): the name/date form, its in-flight state, and the
// rename failure vocabulary the heading dialog reports.

/** The longest event window, in whole days, as the create screen states it. */
private val EVENT_WINDOW_MAX_DAYS: Long = EVENT_WINDOW_MAX_SECONDS.seconds.inWholeDays

/**
 * The create-event landing layer (create-event) — the app's front door for a HOST, brought to the
 * same design language as the join gate. It reads as an invitation being *authored*: the compact host
 * header (the real app mark + "HOST AN EVENT" eyebrow + title + one warm line) leads, then the one
 * question the surface asks — what is it called — with the name field answering it, then the event's
 * date range as a stated-consequence card. Create + the scan hint stay pinned to the bottom.
 *
 * The header is the compact (left-aligned) form so identity costs one line-pair: the short form below —
 * and the transient "creating …" state that replaces it ([CreatingEventScreen]) — stay anchored in the
 * same place, so the surface never jumps between the two.
 *
 * The name and the date range live in the [draft] (only the submitted values cross the container);
 * Create is disabled until the trimmed name is non-empty AND the range satisfies `start < end` AND it is
 * no longer than the backend's event window — which the picker already cannot produce, so the last guard
 * only restates it — and the field caps at 100 characters. A returned failure is therefore a *submission*
 * failure (the server was unreachable or rejected it), not the current input being malformed. It is
 * stated in an [AppErrorBanner] above the action, never as a red field, which would falsely blame the
 * host's typing.
 *
 * The range defaults to **`[now, now + 1 day]`, frozen at first composition** (see [rememberCreateDraft]).
 * A slow typer therefore sets a start a few minutes in the past — harmless, since they are at their own
 * event.
 */
@Composable
internal fun CreateEventScreen(
    state: Layer.CreateEvent,
    draft: CreateDraft,
    onCreateEvent: (String, LocalDateTime, LocalDateTime) -> Unit,
    cutoff: CutoffFormatter,
) {
    // A returned failure — a scanned-invalid-link (transient) or a creation failure reduced into
    // `state.error` — is a submission-level condition, not a live field error, so it is banished to a
    // banner above the action rather than reddening the name field.
    // ONE banner, ONE value. The reduction already coalesced the two causes — a sticky create failure
    // and a self-clearing invalid-link error, the transient winning — so the screen renders what it is
    // given rather than re-deciding the precedence at the render site.
    val bannerError: String? = state.error
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
                    value = draft.name,
                    onValueChange = { draft.name = it },
                    placeholder = "Event name",
                    maxLength = EVENT_NAME_MAX_LENGTH,
                )
            }
            AppEventDateRangeSection(
                from = draft.from,
                until = draft.until,
                rangeLabel = { f, u -> appRangeLabel(f, u) },
                // The live humanized duration hint (capability `create-event`), e.g. "Event lasts 5 days".
                durationLabel = { f, u -> "Event lasts ${cutoff.humanizedDuration(f, u)}" },
                // The truthfulness line: this window is the event's capture-date bound
                // (capability `photo-sharing`) — stated once, where it is set — and the one limit on it, so
                // a picker that will not reach further is explained rather than merely stubborn.
                note = "Only photos taken during this window are shared — the range every guest starts " +
                    "from. An event can last up to $EVENT_WINDOW_MAX_DAYS days.",
                latestUntil = cutoff::latestEnd,
                onRangeChange = { f, u -> draft.from = f; draft.until = u },
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
                onClick = { onCreateEvent(draft.name, draft.from, draft.until) },
                // Disabled while the name is blank OR the range is not `start < end` OR it is longer
                // than the event window.
                enabled = draft.name.isNotBlank() && draft.from < draft.until &&
                    cutoff.fitsEventWindow(draft.from, draft.until),
            )
            StatusHint("Or scan a QR code in the Camera app to join one.")
        }
    }
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
        layer.storeUrl?.let { url ->
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                PrimaryButton(label = "Open the App Store", onClick = { onOpenLink(url) })
            }
        }
    }
}
