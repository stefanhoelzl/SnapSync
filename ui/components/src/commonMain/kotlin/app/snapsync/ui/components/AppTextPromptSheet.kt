package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The text-prompt sheet — the app's bottom sheet for collecting one piece of text, with a confirm and a
 * cancel action.
 *
 * **General, not purpose-named.** It serves the diagnostic dump's bug report (capability
 * `diagnostic-logging`) and the event rename (capability `event-rename`) as ONE component. It was
 * `AppBugReportSheet` while it had a single caller; the second caller wanted the same sheet with a
 * pre-filled value, an error slot, and a busy state, and a second near-identical overlay would have been
 * two components for one meaning. The inventory grows demand-driven with the screens that need it.
 *
 * Semantic and appearance-free like every `App*` component: a [title], an optional [body] line naming
 * what the value is for, a [placeholder] for the input, an [initialValue] it opens carrying, a
 * [maxLength], the two action labels, an optional [error], a [busy] flag, [onConfirm] receiving what was
 * written, and [onDismiss]. No colors, shapes, text styles, `Modifier`, or content slot in the
 * signature, and no Material 3 type — the `ModalBottomSheet`, the input, and the action arrangement all
 * live inside (design-system containment rule).
 *
 * **The component owns keyboard avoidance.** A sheet whose confirm action can be hidden under the
 * software keyboard is unusable, and that is one problem solved once here rather than by every caller.
 * Call sites are given no insets to manage.
 *
 * It is solved by **expanding to full height** rather than by insets alone. `imePadding()` is applied
 * and kept, but it is not what makes this work: measured on an SE2 (iOS 26.5, Compose Multiplatform
 * 1.11.1), a wrap-height sheet with `imePadding()` left the send action **completely behind the
 * keyboard** — the IME inset does not reach the sheet's own popup window, so the padding resolved to
 * zero and nothing moved. Skipping the partially-expanded state lays the content out from the TOP of a
 * full-height sheet, which puts the field and both actions above the keyboard whether or not any inset
 * is ever reported. The scroll then covers the remaining case (a small screen in landscape).
 *
 * Do not "simplify" this back to a wrap-height sheet on the grounds that `imePadding()` is already
 * there. That is precisely the combination that was measured failing.
 *
 * **The input is the component's own state**, seeded from [initialValue]. The caller learns what was
 * written only on [onConfirm], because a half-typed sentence is not something any screen's state should
 * have to carry — the same reason the confirm dialogs keep their visibility local. The seed is keyed on
 * [initialValue], so re-opening the sheet for a different value starts from that value rather than from
 * a stale edit.
 *
 * [onConfirm] receives the **trimmed** text, and confirming is impossible while that trimmed text is
 * empty **or equal to the trimmed [initialValue]** — so a caller editing an existing value cannot submit
 * a no-op. Both are disabled rather than validated, so an invalid submit is unreachable and neither ever
 * needs an error message. (For the always-empty [initialValue] of the bug report, the second rule
 * collapses into the first and nothing about that caller changes.)
 *
 * [error] is for the failure a client-side rule CANNOT prevent — a rejection from a remote system. It
 * renders as an [AppErrorBanner] above the actions, never as a styling of the input: a server saying no
 * must not read as a complaint about what the person typed. (The create screen's spec makes the same
 * call for the same reason.)
 *
 * While [busy] the sheet stays open, the confirm action states that it is running, and **every dismissal
 * route is refused** — cancel, the scrim, and the swipe-down alike. An in-flight request must be neither
 * double-submitted nor abandoned half-way, and the only honest thing a sheet can do while it waits is
 * stay put. Otherwise cancel, the scrim, and the swipe-down gesture all route to [onDismiss] — one
 * dismissal, however it is spelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTextPromptSheet(
    title: String,
    placeholder: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
    initialValue: String = "",
    maxLength: Int = Int.MAX_VALUE,
    error: String? = null,
    busy: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    val written = text.trim()
    // A no-op submission is unreachable, not merely rejected. For an empty seed this IS the empty check.
    val submittable = written.isNotEmpty() && written != initialValue.trim()

    ModalBottomSheet(
        // Busy refuses the scrim and the swipe-down as firmly as it refuses the cancel button: a request
        // in flight has no honest cancellation, so there is one answer for every dismissal route.
        onDismissRequest = { if (!busy) onDismiss() },
        // Full height, so the content is laid out from the top and the keyboard cannot reach the
        // actions. See the note above: this is the load-bearing half of keyboard avoidance, not a
        // presentation preference.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Pinned to the frozen scheme: an unpinned M3 surface falls back to the Material baseline
        // tonal surface — the lavender cast on our green palette that keeps resurfacing.
        containerColor = scheme.surface,
        contentColor = scheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // IME first, then the navigation-bar inset, then scrolling: the content rises with the
                // keyboard, keeps clear of the home indicator, and scrolls only when it still overflows.
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface,
            )
            AppTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = placeholder,
                maxLength = maxLength,
                singleLine = false,
                enabled = !busy,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            // The remote rejection, above the actions and never on the field.
            if (error != null) {
                AppErrorBanner(text = error)
            }
            PrimaryButton(
                label = confirmLabel,
                onClick = { onConfirm(written) },
                enabled = submittable && !busy,
            )
            SecondaryButton(label = cancelLabel, onClick = { if (!busy) onDismiss() })
        }
    }
}
