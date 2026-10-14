package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import app.snapsync.ui.components.AppChoiceSheet
import app.snapsync.ui.components.AppEventHeaderHost
import app.snapsync.ui.components.AppEventRangePicker
import app.snapsync.ui.components.AppFooterTextActions
import app.snapsync.ui.components.AppIdentityHeader
import app.snapsync.ui.components.AppNetworkNotice
import app.snapsync.ui.components.AppQuestionHeading
import app.snapsync.ui.components.AppTextField
import app.snapsync.ui.components.EndTimeGuide
import app.snapsync.ui.components.KeepOpenTextAction
import app.snapsync.ui.components.LocalDateFormats
import app.snapsync.ui.components.PrimaryButton
import app.snapsync.ui.components.StatusHero
import app.snapsync.ui.components.StatusHint
import app.snapsync.ui.components.StatusIndicator
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.cancel
import app.snapsync.ui.resources.create_button
import app.snapsync.ui.resources.create_join_hint
import app.snapsync.ui.resources.create_lasts
import app.snapsync.ui.resources.create_name_placeholder
import app.snapsync.ui.resources.create_name_question
import app.snapsync.ui.resources.create_step_end_time
import app.snapsync.ui.resources.create_step_name
import app.snapsync.ui.resources.create_title
import app.snapsync.ui.resources.create_when_question
import app.snapsync.ui.resources.create_window_note
import app.snapsync.ui.resources.creating
import app.snapsync.ui.resources.duration_days
import app.snapsync.ui.resources.hero_subtitle
import app.snapsync.ui.resources.keep_open_body
import app.snapsync.ui.resources.keep_open_choice
import app.snapsync.ui.resources.keep_open_title
import app.snapsync.ui.resources.message_report_this
import app.snapsync.ui.resources.store_open
import app.snapsync.ui.resources.update_detail
import app.snapsync.ui.resources.update_detail_version
import app.snapsync.ui.resources.update_eyebrow
import app.snapsync.ui.resources.update_headline
import app.snapsync.ui.resources.update_subtitle
import app.snapsync.ui.resources.update_title
import app.snapsync.ui.state.CreateStep
import app.snapsync.ui.state.EVENT_NAME_MAX_LENGTH
import app.snapsync.ui.state.KeepOpenSheet
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.ScreenMessage
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// Event creation: the name/date form and its in-flight state.

/**
 * The create-event landing layer (create-event) — the app's front door for a HOST, brought to the
 * same design language as the join gate. It reads as an invitation being *authored*: the compact host
 * header leads, then the two questions the surface asks — what is it called, and when is it — with the name
 * field and the inline range picker answering them. Create + one line under it stay pinned to the bottom.
 *
 * The form is the app's ([Layer.CreateEvent.form]): the name, the range and what they make arrive in the state, and
 * every edit crosses as an intent. The range has NO complete default: the start is preset to now (following the
 * clock until the host chooses something in the range) and the last day to today, but the end TIME must be chosen,
 * because a pre-filled window the host never looked at was almost always wrong and silently bounds every member's
 * photos. Create is offered only once the form may be created; a line ABOVE Create
 * names the next missing step — a button that leads to it: the name field, focused with the keyboard up, or the
 * end-time wheels, brought into view and outlined — and turns into the event's duration once there is none.
 *
 * A returned failure is a *submission* failure (the server was unreachable or rejected it), not the current
 * input being malformed. It replaces the scan hint BELOW Create — never a red field, which would blame the
 * host's typing, and never a banner that grows the pinned area over the range picker.
 */
@Composable
internal fun CreateEventScreen(state: Layer.CreateEvent, callbacks: CreateCallbacks) {
    val form = state.form
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
    // The text being edited is the field's own (the input-mechanics exception, `ScreenStateContainmentTest`): a field
    // fed from the app's state a frame late would lose keystrokes and break the keyboard's composition. Each edit is
    // handed on, and the app's name is what decides; a fresh draft seeds the field again.
    var name by remember(form.draft) { mutableStateOf(form.name) }
    Column(modifier = Modifier.fillMaxSize()) {
        // Identity, pinned to the top so it holds its place across the form / creating swap.
        AppEventHeaderHost(
            title = stringResource(Res.string.create_title),
            subtitle = stringResource(Res.string.hero_subtitle),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CreateQuestion(stringResource(Res.string.create_name_question), Modifier.padding(top = 18.dp)) {
                AppTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        callbacks.create.onName(it)
                    },
                    placeholder = stringResource(Res.string.create_name_placeholder),
                    maxLength = EVENT_NAME_MAX_LENGTH,
                    focusRequester = nameFocus,
                )
            }
            CreateQuestion(stringResource(Res.string.create_when_question)) {
                AppEventRangePicker(
                    picker = form.picker,
                    // The truthfulness line: this window is the event's capture-date bound — stated once, where it is
                    // set — and the one limit on it.
                    note = stringResource(Res.string.create_window_note, form.maxDays),
                    gestures = callbacks.create.range,
                    endTime = EndTimeGuide(showRequests = endTimeRequests, onPickEndTime = guide.toEndTime),
                )
            }
        }
        CreateActionsBar(state, callbacks, guide)
    }
    form.keepOpen?.let { KeepOpenQuestion(it, callbacks.create) }
}

/**
 * The question Create asks first for a range that is already over: how long others may
 * still join — a choice each, beside the day it leads to, every one creating at once — or Cancel, which keeps the form.
 */
@Composable
private fun KeepOpenQuestion(sheet: KeepOpenSheet, actions: CreateActions) {
    val dates = LocalDateFormats.current
    AppChoiceSheet(
        title = stringResource(Res.string.keep_open_title),
        body = stringResource(Res.string.keep_open_body),
        cancelLabel = stringResource(Res.string.cancel),
        onDismiss = actions.onKeepOpenDismiss,
    ) {
        for (choice in sheet.choices) {
            val label = stringResource(
                Res.string.keep_open_choice,
                pluralStringResource(Res.plurals.duration_days, choice.days, choice.days),
                dates.format(LocalDateTime(choice.until, LocalTime(0, 0)), "MMMEd"),
            )
            AppFooterTextActions { KeepOpenTextAction(label = label, onClick = { actions.onKeepOpenFor(choice.days) }) }
        }
    }
}

/** Where tapping the named next missing step leads. */
private class StepGuide(val toName: () -> Unit, val toEndTime: () -> Unit)

/** One question the form asks, over the control that answers it. */
@Composable
private fun CreateQuestion(question: String, modifier: Modifier = Modifier, answer: @Composable () -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppQuestionHeading(question)
        answer()
    }
}

/** What the create screen can ask for: the form's own, and SnapSync's Settings page when its network is blocked. */
internal class CreateCallbacks(
    val create: CreateActions,
    val onOpenSettings: () -> Unit,
    /** "Report this" on a refusal the user can only tell us about. */
    val onReportRefusal: (ScreenMessage) -> Unit,
) {
    /** The tap that reports [message]. */
    fun reporting(message: ScreenMessage): () -> Unit = { onReportRefusal(message) }
}

/**
 * The pinned bottom: what is still missing (or how long the event lasts), Create, and one line under it —
 * the scan hint, or in its place the failure, or above both a missing network, which
 * also takes Create away. A constant height, so nothing here ever covers the picker.
 */
@Composable
private fun CreateActionsBar(state: Layer.CreateEvent, callbacks: CreateCallbacks, guide: StepGuide) {
    // ONE value: the reduction already coalesced a sticky create failure and a self-clearing invalid-link
    // notice, the transient winning, so the screen renders what it is given.
    val error: String? = state.error?.text()
    val form = state.form
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val toStep = when (form.nextStep) {
            CreateStep.Name -> guide.toName
            CreateStep.EndTime -> guide.toEndTime
            is CreateStep.Complete -> null
        }
        StatusHint(nextStepLine(form.nextStep).text(), onClick = toStep)
        PrimaryButton(
            label = stringResource(Res.string.create_button),
            onClick = callbacks.create.onCreate.takeIf { form.canCreate && state.network == null },
        )
        val network = state.network
        if (network != null) {
            // The failure an earlier attempt left is kept by the reduction and returns with the network.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AppNetworkNotice(blocked = network == NetworkNotice.BLOCKED, onOpenSettings = callbacks.onOpenSettings)
            }
        } else {
            // A refusal the user can only tell us about offers the report in the SAME line, so the bottom keeps its
            // constant height: the front screen tells a refused phone before it tries.
            val reportable = state.error?.takeIf { it.offersReport }
            StatusHint(
                text = if (reportable != null) {
                    "$error ${stringResource(Res.string.message_report_this)}"
                } else {
                    error ?: stringResource(Res.string.create_join_hint)
                },
                isError = error != null,
                onClick = reportable?.let(callbacks::reporting),
            )
        }
    }
}

/** The line above Create: the next missing step, or — once there is none — the event's duration. */
internal fun nextStepLine(step: CreateStep): Phrase = when (step) {
    CreateStep.Name -> Phrase.Of(Res.string.create_step_name)
    CreateStep.EndTime -> Phrase.Of(Res.string.create_step_end_time)
    is CreateStep.Complete -> Phrase.Of(Res.string.create_lasts, listOf(step.lasts.phrase()))
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
            title = stringResource(Res.string.create_title),
            subtitle = stringResource(Res.string.hero_subtitle),
        )
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusHero(StatusIndicator.Loading, stringResource(Res.string.creating))
        }
    }
}

/**
 * The backend refuses this build as too old.
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
internal fun UpdateRequiredScreen(layer: Layer.UpdateRequired, onOpenStore: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Its OWN eyebrow. Reaching for `AppEventHeaderHost` here put "HOST AN EVENT" above this screen
        // on a real device — a surface borrowing another's verb, which no state assertion could see.
        AppIdentityHeader(
            eyebrow = stringResource(Res.string.update_eyebrow),
            title = stringResource(Res.string.update_title),
            subtitle = stringResource(Res.string.update_subtitle),
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
                stringResource(Res.string.update_headline),
                layer.minimumVersion
                    ?.let { stringResource(Res.string.update_detail_version, it) }
                    ?: stringResource(Res.string.update_detail),
            )
        }
        layer.store?.let { store ->
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                PrimaryButton(label = stringResource(Res.string.store_open, store), onClick = onOpenStore)
            }
        }
    }
}
