package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.feature.membership.RenameFailureReason
import app.snapsync.presentation.UiState
import app.snapsync.ui.components.AppErrorBanner
import app.snapsync.ui.components.AppEventHeaderHost
import app.snapsync.ui.components.AppEventDateRangeSection
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import app.snapsync.ui.components.AppQuestionHeading
import app.snapsync.ui.components.AppTextField
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.StatusHero
import app.snapsync.ui.components.StatusHint
import app.snapsync.ui.components.StatusIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// Event creation (capability `event-creation-ui`): the name/date form, its in-flight state, and the
// rename failure vocabulary the heading dialog reports.

/**
 * The create-event landing layer (event-creation-ui) — the app's front door for a HOST, brought to the
 * same design language as the join gate. It reads as an invitation being *authored*: the compact host
 * header (the real app mark + "HOST AN EVENT" eyebrow + title + one warm line) leads, then the one
 * question the surface asks — what is it called — with the name field answering it, then the event's
 * date range as a stated-consequence card. Create + the scan hint stay pinned to the bottom.
 *
 * The header is the compact (left-aligned) form so identity costs one line-pair: the short form below —
 * and the transient "creating …" state that replaces it ([CreatingEventScreen]) — stay anchored in the
 * same place, so the surface never jumps between the two.
 *
 * The name and the date range live in local Compose state (only the submitted values cross the container);
 * Create is disabled until the trimmed name is non-empty AND the range satisfies `start < end`, and the
 * field caps at 100 characters — so a returned failure is a *submission* failure (the server was
 * unreachable or rejected it), not the current name being malformed. It is therefore stated in an
 * [AppErrorBanner] above the action, never as a red field, which would falsely blame the host's typing.
 *
 * The range defaults to **`[now, now + 1 day]`, frozen at first composition** (`remember { … }`, not
 * re-derived at submit). The label is the screen's whole statement about what will be sent, so a value that
 * silently drifted between being displayed and being posted would make the screen lie. A slow typer
 * therefore sets a start a few minutes in the past — harmless, since they are at their own event.
 */
@Composable
internal fun CreateEventScreen(
    state: UiState.CreateEvent,
    onCreateEvent: (String, LocalDateTime, LocalDateTime) -> Unit,
    transientError: String?,
    cutoff: CutoffFormatter,
) {
    var name by remember { mutableStateOf("") }
    // The default window `[now, now + 1 day]`, FROZEN at first composition (not re-derived at submit): the
    // label is the screen's whole statement of what will be sent, so a value that drifted between display
    // and post would make it lie.
    val initialFrom = remember { cutoff.nowLocal() }
    val initialUntil = remember(initialFrom) {
        val next = initialFrom.date.plus(1, DateTimeUnit.DAY)
        LocalDateTime(next.year, next.month.ordinal + 1, next.day, initialFrom.hour, initialFrom.minute)
    }
    var from by remember { mutableStateOf(initialFrom) }
    var until by remember { mutableStateOf(initialUntil) }
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
            AppEventDateRangeSection(
                from = from,
                until = until,
                rangeLabel = { f, u -> cutoff.formatRange(f, u) },
                // The live humanized duration hint (capability `event-creation-ui`), e.g. "Event lasts 5 days".
                durationLabel = { f, u -> "Event lasts ${cutoff.humanizedDuration(f, u)}" },
                // The truthfulness line: this window is the event's capture-date bound
                // (capability `photo-selection-policy`) — stated once, where it is set.
                note = "Only photos taken during this window are shared — the range every guest starts from.",
                onRangeChange = { f, u -> from = f; until = u },
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
                onClick = { onCreateEvent(name, from, until) },
                // Disabled while the name is blank OR the range is not `start < end`.
                enabled = name.isNotBlank() && from < until,
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

// Mirrors the backend's name cap (trimmed, non-empty, ≤100) so a server 400 is near-unreachable.
private const val EVENT_NAME_MAX_LENGTH = 100

/**
 * The rename dialog's failure copy (capability `event-rename`). Two reasons, because the port reports
 * two: the backend rejected the name, or everything else.
 *
 * There is deliberately no "this event no longer exists" copy for the `404` that also arrives as
 * [RenameFailureReason.SERVER]. A `404` here is a single witness that the event is gone, and the
 * self-leave needs two (capability `leave-event`); giving it copy would give it a meaning, and a meaning
 * invites acting on it. The standing foreground refresh reaches that verdict on its own terms.
 */
internal fun renameFailureText(reason: RenameFailureReason): String = when (reason) {
    RenameFailureReason.INVALID_NAME -> "That name wasn't accepted. Try a shorter one."
    RenameFailureReason.SERVER -> "Couldn't rename the event. Check your connection and try again."
}
