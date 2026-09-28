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
 * The information sheet — a bottom sheet that explains something on request and changes nothing: a
 * [title], the caller's [content], and one [dismissLabel] action. Every dismissal route (the action, the
 * scrim, the swipe-down) is the same [onDismiss].
 *
 * It exists for the join screen's photo-access explanation (capability `join-event`), which used to be a
 * whole step every first-time guest passed through and is now one tap away (decision record
 * `simplify-join-screen`, D3). Reading it must raise nothing — the sheet has no action but closing.
 *
 * Pinned to the frozen scheme for the reason [AppTextPromptSheet] is: an unpinned M3 surface falls back to
 * the Material baseline tonal surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoSheet(
    title: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
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
            content()
            PrimaryButton(label = dismissLabel, onClick = onDismiss)
        }
    }
}
