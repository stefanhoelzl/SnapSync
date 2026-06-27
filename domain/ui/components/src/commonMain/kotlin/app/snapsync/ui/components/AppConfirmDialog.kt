package app.snapsync.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * A semantic confirm/cancel dialog. The call site passes only the [title] text, the two button
 * labels, and the [onConfirm] / [onDismiss] callbacks — the dialog's visual form (the modal scrim,
 * button placement, typography) is the skin's. Dismissing via the scrim or back gesture routes to
 * [onDismiss], identical to tapping cancel.
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
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelLabel) } },
    )
}
