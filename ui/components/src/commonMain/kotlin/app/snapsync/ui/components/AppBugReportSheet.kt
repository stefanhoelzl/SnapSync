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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The bug-report sheet — the app's first bottom sheet — which collects a written account of a problem
 * and offers a confirm and a cancel action (capability `diagnostic-logging`).
 *
 * Semantic and appearance-free like every `App*` component: a [title], a [body] line naming what will
 * be sent, a [placeholder] for the input, a [maxLength], the two action labels, [onConfirm] receiving
 * what was written, and [onDismiss]. No colors, shapes, text styles, `Modifier`, or content slot in the
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
 * **The input is the component's own state.** The caller learns what was written only on [onConfirm],
 * because a half-typed sentence is not something any screen's state should have to carry — the same
 * reason the confirm dialogs keep their visibility local.
 *
 * [onConfirm] receives the **trimmed** text, and confirming is impossible while that trimmed text is
 * empty: the action is disabled rather than validated, so an invalid submit is unreachable and no error
 * message is ever needed. Cancel, the scrim, and the swipe-down gesture all route to [onDismiss] —
 * one dismissal, however it is spelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBugReportSheet(
    title: String,
    body: String,
    placeholder: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    maxLength: Int = Int.MAX_VALUE,
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    val written = text.trim()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            PrimaryButton(
                label = confirmLabel,
                onClick = { onConfirm(written) },
                enabled = written.isNotEmpty(),
            )
            SecondaryButton(label = cancelLabel, onClick = onDismiss)
        }
    }
}
