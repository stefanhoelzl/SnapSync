package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A semantic confirm/cancel dialog. The call site passes only the [title] text, the two button
 * labels, and the [onConfirm] / [onDismiss] callbacks — the dialog's visual form (the modal scrim,
 * button placement, typography) is the skin's. The two sit **side by side** and share one button
 * geometry, differing only in emphasis: cancel is the [SecondaryButton] (outlined) on the left, and
 * the confirm action is the [PrimaryButton] (filled) on the right — it completes the intent the user
 * already initiated. Dismissing via the scrim or back gesture routes to [onDismiss], identical to cancel.
 */
@Composable
fun AppConfirmDialog(
    title: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) { SecondaryButton(label = cancelLabel, onClick = onDismiss) }
                Box(Modifier.weight(1f)) { PrimaryButton(label = confirmLabel, onClick = onConfirm) }
            }
        },
    )
}
