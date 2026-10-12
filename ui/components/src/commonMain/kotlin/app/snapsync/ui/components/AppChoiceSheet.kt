package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The choice sheet — a bottom sheet that asks one question before something happens: a [title], the [body] that
 * explains it, the caller's [choices] (footer actions, a row each, as the joined screen's footer draws them), and
 * [cancelLabel] beneath them. Every way out without a choice — the cancel action, the scrim, the swipe-down — is the
 * same [onDismiss].
 *
 * It exists for the create form's back-dated question, "how long should guests have to join?" (decision record
 * `changes/back-dated-events`, D9). Pinned to the frozen scheme for the reason [AppTextPromptSheet] is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppChoiceSheet(
    title: String,
    body: String,
    cancelLabel: String,
    onDismiss: () -> Unit,
    choices: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surface,
        contentColor = scheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            HorizontalDivider(color = scheme.outlineVariant)
            Column(content = choices)
            HorizontalDivider(color = scheme.outlineVariant)
            AppFooterTextActions { CancelTextAction(label = cancelLabel, onClick = onDismiss) }
        }
    }
}
