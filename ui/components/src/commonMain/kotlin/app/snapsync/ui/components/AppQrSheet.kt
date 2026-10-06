package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The invite's QR code, shown on request in a bottom sheet (capability `manage-membership`): a [title] naming
 * the event to join, the code for [content], and a [caption] addressed to the member holding the phone. It has
 * no action: every dismissal route (the scrim, the swipe-down) is the same [onDismiss], and showing it changes
 * nothing. The code stays dark on its white card in both appearances ([AppQrCode]).
 *
 * Pinned to the frozen scheme for the reason [AppTextPromptSheet] is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppQrSheet(title: String, content: String, caption: String, onDismiss: () -> Unit) {
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
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            AppQrCode(content = content, caption = caption)
        }
    }
}
